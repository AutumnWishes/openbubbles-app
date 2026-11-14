// SPDX-License-Identifier: AGPL-3.0-or-later
// Package bridge exposes a deliberately small, foreground-only Google Messages
// linked-device experiment to gomobile. It never sends without an explicit Send.
package bridge

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"
	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/events"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
	"go.mau.fi/util/exhttp"
	"google.golang.org/protobuf/proto"
)

const maxJournal = 256
const operationTimeout = 45 * time.Second

// Listener is implemented by Android. OnSession must synchronously encrypt and
// atomically persist the secret JSON, returning false if that fails. An empty
// string means delete the saved session. Callbacks may run off the UI thread.
// Neither callback may synchronously call back into this Client.
type Listener interface {
	OnEvent(eventJSON string)
	OnSession(secretJSON string) bool
}

// wire limits the network capabilities used by the harness and permits offline
// tests. No mark-read, typing, deletion, conversation creation or SMS fallback.
type wire interface {
	ListConversations(context.Context, *gmproto.ListConversationsRequest) (*gmproto.ListConversationsResponse, error)
	GetConversation(context.Context, string) (*gmproto.Conversation, error)
	FetchMessages(context.Context, string, int64, *gmproto.Cursor) (*gmproto.ListMessagesResponse, error)
	SendMessage(context.Context, *gmproto.SendMessageRequest) (*gmproto.SendMessageResponse, error)
	IsBugleDefault(context.Context) (*gmproto.IsBugleDefaultResponse, error)
	Disconnect()
}
type sessionData struct {
	Version int                     `json:"version"`
	Auth    *libgm.AuthData         `json:"auth"`
	Journal map[string]journalEntry `json:"journal"`
}
type queuedEvent struct {
	generation uint64
	value      any
}

// Client owns one pairing. Public operations other than Disconnect are
// serialized. Disconnect cancels a pending Pair immediately. No network traffic
// occurs during construction, Snapshot, or Forget.
type Client struct {
	opMu                        sync.Mutex
	eventMu                     sync.Mutex
	mu                          sync.Mutex
	persistMu                   sync.Mutex
	listener                    Listener
	auth                        *libgm.AuthData
	network                     wire
	ctx                         context.Context
	cancel                      context.CancelFunc
	generation                  uint64
	state, lastError, challenge string
	ready                       bool
	storageFailed               bool
	sims                        map[string]*gmproto.SIMCard
	journal                     map[string]journalEntry
}

// NewClient restores an encrypted-at-rest session supplied by Android. The JSON
// must never be logged, included in an intent, or returned in an event.
func NewClient(savedSessionJSON string, listener Listener) (*Client, error) {
	if listener == nil {
		return nil, errors.New("LISTENER_REQUIRED: secure session storage is required")
	}
	c := &Client{listener: listener, state: "disconnected", sims: make(map[string]*gmproto.SIMCard), journal: make(map[string]journalEntry)}
	if savedSessionJSON != "" {
		var s sessionData
		if len(savedSessionJSON) > 1024*1024 || json.Unmarshal([]byte(savedSessionJSON), &s) != nil || s.Version != 1 || !validAuth(s.Auth) || len(s.Journal) > maxJournal {
			return nil, errors.New("SESSION_INVALID: saved pairing cannot be restored; forget it and pair again")
		}
		c.auth = s.Auth
		for id, e := range s.Journal {
			if _, err := uuid.Parse(id); err != nil || e.RequestID != id || len(e.Hash) != 64 {
				return nil, errors.New("SESSION_INVALID: saved send history is invalid")
			}
			if e.Status == "submitting" {
				e.Status = "unknown"
				e.Message = "Interrupted send. Check this conversation before sending again."
			}
			c.journal[id] = e
		}
	}
	return c, nil
}
func validAuth(a *libgm.AuthData) bool {
	return a != nil && a.Browser != nil && a.Mobile != nil && a.RefreshKey != nil && a.RequestCrypto != nil && len(a.RequestCrypto.AESKey) == 32 && len(a.RequestCrypto.HMACKey) == 32 && a.DestRegID != uuid.Nil && a.HasCookies()
}
func (c *Client) emit(v any) { c.listener.OnEvent(encode(v)) }
func (c *Client) fail(code, message string) error {
	c.mu.Lock()
	c.lastError = message
	c.mu.Unlock()
	c.emit(map[string]any{"type": "error", "code": code, "message": message})
	return errors.New(code + ": " + message)
}
func (c *Client) setState(state, message string) {
	c.mu.Lock()
	c.state = state
	c.ready = state == "ready"
	if state != "awaiting_confirmation" {
		c.challenge = ""
	}
	c.mu.Unlock()
	c.emit(map[string]any{"type": "state", "state": state, "message": message})
}
func (c *Client) current(gen uint64) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.generation == gen
}
func (c *Client) start(a *libgm.AuthData, publishAuth bool) (*libgm.Client, context.Context, uint64) {
	c.Disconnect()
	ctx, cancel := context.WithCancel(zerolog.Nop().WithContext(context.Background()))
	n := libgm.NewClient(a, nil, zerolog.Nop(), exhttp.SensibleClientSettings)
	c.eventMu.Lock()
	c.mu.Lock()
	if publishAuth {
		c.auth = a
	}
	c.ctx = ctx
	c.cancel = cancel
	c.network = n
	c.generation++
	gen := c.generation
	c.sims = make(map[string]*gmproto.SIMCard)
	c.lastError = ""
	c.mu.Unlock()
	c.eventMu.Unlock()
	// A connection-scoped consumer preserves event order without retaining a
	// stopped Client forever. Java/disk callbacks run outside libgm's dispatcher.
	queue := make(chan queuedEvent, 256)
	go func() {
		for {
			select {
			case e := <-queue:
				c.handleEvent(e)
			case <-ctx.Done():
				return
			}
		}
	}()
	n.SetEventHandler(func(event any) {
		select {
		case <-ctx.Done():
			return
		case queue <- queuedEvent{gen, event}:
		default:
			// Losing observations makes delivery state unsafe. Stop instead of
			// quietly allowing a new send on incomplete state.
			cancel()
		}
	})
	return n, ctx, gen
}

func parseCookies(input string) (map[string]string, error) {
	var values map[string]string
	if len(input) > 64*1024 || json.Unmarshal([]byte(input), &values) != nil {
		return nil, errors.New("invalid cookie JSON")
	}
	allowed := map[string]bool{"SID": true, "HSID": true, "SSID": true, "APISID": true, "SAPISID": true, "OSID": true, "__Secure-1PSIDTS": true}
	for k, v := range values {
		if !allowed[k] || v == "" || len(v) > 8192 || strings.ContainsAny(v, ";\"\\,") {
			return nil, errors.New("invalid cookie fields")
		}
		for _, b := range []byte(v) {
			if b < 0x21 || b > 0x7e {
				return nil, errors.New("invalid cookie value")
			}
		}
	}
	for _, k := range []string{"SID", "HSID", "SSID", "APISID", "SAPISID", "OSID"} {
		if values[k] == "" {
			return nil, errors.New("missing cookie fields")
		}
	}
	return values, nil
}

// Pair consumes only locally imported cookie JSON and waits for the user to
// confirm the displayed challenge on their single Google Messages phone.
func (c *Client) Pair(cookiesJSON string) error {
	c.opMu.Lock()
	defer c.opMu.Unlock()
	c.mu.Lock()
	paired := validAuth(c.auth)
	c.mu.Unlock()
	if paired {
		return c.fail("ALREADY_PAIRED", "Forget the existing pairing before importing a new account.")
	}
	cookies, err := parseCookies(cookiesJSON)
	if err != nil {
		return c.fail("COOKIES_INVALID", "Import a JSON object containing the six required Google Messages cookie values.")
	}
	a := libgm.NewAuthData()
	a.SetCookies(cookies)
	n, base, gen := c.start(a, false)
	ctx, cancel := context.WithTimeout(base, 3*time.Minute)
	defer cancel()
	c.setState("pairing", "Looking for your Google Messages phone.")
	failure := func(err error) error {
		c.mu.Lock()
		if c.auth == a {
			c.auth = nil
		}
		current := c.generation == gen
		c.mu.Unlock()
		if !current {
			n.Disconnect()
			return errors.New("CANCELLED: pairing cancelled")
		}
		c.Disconnect()
		c.setState("error", "Pairing did not complete.")
		code, message := pairError(err)
		return c.fail(code, message)
	}
	if err = n.FetchConfig(ctx); err != nil {
		return failure(err)
	}
	challenge, pairing, err := n.StartGaiaPairing(ctx, ctx)
	if err != nil {
		return failure(err)
	}
	if !c.current(gen) {
		return failure(context.Canceled)
	}
	c.setState("awaiting_confirmation", "Confirm the matching symbol in Google Messages on your phone.")
	c.mu.Lock()
	c.challenge = challenge
	c.mu.Unlock()
	c.emit(map[string]any{"type": "challenge", "value": challenge, "message": "Choose this symbol in Google Messages to confirm the linked device."})
	if _, err = n.FinishGaiaPairing(ctx, pairing); err != nil {
		return failure(err)
	}
	if !c.current(gen) {
		return failure(context.Canceled)
	}
	// Pairing mutates auth fields without snapshot locks upstream. Publish the
	// completed auth only after the cryptographic handshake has finished.
	c.eventMu.Lock()
	c.mu.Lock()
	if c.generation == gen {
		c.auth = a
	}
	c.mu.Unlock()
	c.eventMu.Unlock()
	if !c.persist() {
		return failure(errors.New("storage failed"))
	}
	cancel()
	n.Disconnect()
	if !c.current(gen) {
		return errors.New("CANCELLED: connection cancelled; pairing was saved")
	}
	c.setState("connecting", "Pairing saved. Connecting to the phone.")
	if err = n.Reconnect(base); err != nil {
		c.Disconnect()
		c.setState("offline", "Pairing saved, but the phone could not reconnect.")
		return c.fail("CONNECT_FAILED", "Pairing was saved. Use Connect to retry the phone connection.")
	}
	return nil
}
func pairError(err error) (string, string) {
	switch {
	case errors.Is(err, context.Canceled):
		return "CANCELLED", "Pairing cancelled."
	case errors.Is(err, context.DeadlineExceeded), errors.Is(err, libgm.ErrPairingTimeout), errors.Is(err, libgm.ErrPairingInitTimeout):
		return "PAIR_TIMEOUT", "Pairing timed out. Open Google Messages and start a fresh pairing."
	case errors.Is(err, libgm.ErrNoDevicesFound):
		return "PAIR_NO_DEVICES", "No Google Messages phone was found for this account."
	case errors.Is(err, libgm.ErrIncorrectEmoji):
		return "PAIR_CHALLENGE", "The pairing symbol did not match. Start a fresh pairing."
	case errors.Is(err, libgm.ErrPairingCancelled):
		return "PAIR_CANCELLED", "Pairing was declined on the phone."
	case err != nil && strings.Contains(err.Error(), "rcs prototype requires exactly one Google Messages phone"):
		return "PAIR_MULTIPLE_PHONES", "This test requires exactly one Google Messages phone on the account; it cannot safely select among several phones."
	case err != nil && err.Error() == "storage failed":
		return "STORAGE_FAILED", "Encrypted session storage failed. Pairing cannot continue."
	default:
		return "PAIR_FAILED", "Google Messages pairing failed. Check your local cookies and phone connection, then try again."
	}
}

// Connect resumes a saved pairing. Ready is reported only after the phone's
// initial update event, not merely after starting an HTTP connection.
func (c *Client) Connect() error {
	c.opMu.Lock()
	defer c.opMu.Unlock()
	c.mu.Lock()
	a := c.auth
	c.mu.Unlock()
	if !validAuth(a) {
		return c.fail("PAIR_REQUIRED", "Pair with Google Messages first.")
	}
	n, ctx, gen := c.start(a, true)
	c.setState("connecting", "Connecting to Google Messages.")
	if err := n.Connect(ctx); err != nil {
		if !c.current(gen) {
			return errors.New("CANCELLED: connection cancelled")
		}
		c.Disconnect()
		c.setState("reauth_required", "Connection failed. Pairing may need to be renewed.")
		return c.fail("CONNECT_FAILED", "Could not connect to Google Messages. Check the phone connection or renew pairing.")
	}
	return nil
}

// Disconnect does not delete credentials or send history. An in-flight send
// remains uncertain until an observed message resolves it.
func (c *Client) Disconnect() {
	// Cancel the network promptly even when a short encrypted write callback
	// is still finishing in the event consumer.
	c.mu.Lock()
	earlyCancel := c.cancel
	c.mu.Unlock()
	if earlyCancel != nil {
		earlyCancel()
	}
	c.eventMu.Lock()
	c.mu.Lock()
	cancel, n := c.cancel, c.network
	c.cancel = nil
	c.network = nil
	c.ctx = nil
	c.generation++
	c.ready = false
	c.state = "disconnected"
	c.challenge = ""
	c.mu.Unlock()
	c.eventMu.Unlock()
	if cancel != nil {
		cancel()
	}
	if n != nil {
		n.Disconnect()
	}
	c.emit(map[string]any{"type": "state", "state": "disconnected", "message": "Disconnected."})
}

// Forget removes this app's local secrets. Remove the linked device from
// Google Messages > Device pairing as well to revoke it on the phone.
func (c *Client) Forget() error {
	c.Disconnect()
	c.opMu.Lock()
	defer c.opMu.Unlock()
	c.persistMu.Lock()
	defer c.persistMu.Unlock()
	removed := c.listener.OnSession("")
	c.mu.Lock()
	c.auth = nil
	c.journal = make(map[string]journalEntry)
	c.sims = make(map[string]*gmproto.SIMCard)
	c.lastError = ""
	c.storageFailed = false
	c.mu.Unlock()
	if !removed {
		return c.fail("STORAGE_FAILED", "This connection was cleared from memory, but encrypted session removal was not confirmed. Try forgetting again.")
	}
	return nil
}

func (c *Client) persist() bool {
	c.persistMu.Lock()
	defer c.persistMu.Unlock()
	c.mu.Lock()
	entries := make(map[string]journalEntry, len(c.journal))
	for id, e := range c.journal {
		entries[id] = e
	}
	s := sessionData{1, c.auth, entries}
	gen := c.generation
	c.mu.Unlock()
	if s.Auth == nil {
		return false
	}
	b, err := json.Marshal(s)
	if err != nil || !c.current(gen) {
		return false
	}
	ok := c.listener.OnSession(string(b))
	if !ok {
		c.mu.Lock()
		c.storageFailed = true
		c.mu.Unlock()
	}
	return ok
}

// Snapshot contains no cookies, auth keys, tokens, or message bodies.
func (c *Client) Snapshot() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	sims := make([]simView, 0, len(c.sims))
	for _, s := range c.sims {
		sims = append(sims, viewSIM(s))
	}
	sort.Slice(sims, func(i, j int) bool { return sims[i].ID < sims[j].ID })
	sends := make([]sendView, 0, len(c.journal))
	hasUncertain := c.storageFailed
	for _, e := range c.journal {
		sends = append(sends, e.sendView)
		hasUncertain = hasUncertain || blocksSend(e)
	}
	sortSends(sends)
	return encode(map[string]any{"state": c.state, "paired": validAuth(c.auth), "ready": c.ready, "sims": sims, "lastError": c.lastError, "challenge": c.challenge, "sends": sends, "hasUncertainSend": hasUncertain})
}
func (c *Client) connection() (wire, context.Context, uint64, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.ready || c.network == nil || c.ctx == nil || c.ctx.Err() != nil {
		return nil, nil, 0, errors.New("NOT_READY: wait for the linked phone to be ready")
	}
	return c.network, c.ctx, c.generation, nil
}

// Conversations lists at most fifty existing inbox conversations.
func (c *Client) Conversations() (string, error) {
	c.opMu.Lock()
	defer c.opMu.Unlock()
	n, base, gen, err := c.connection()
	if err != nil {
		return "", c.fail("NOT_READY", "Wait for the linked phone to be ready.")
	}
	ctx, cancel := context.WithTimeout(base, operationTimeout)
	defer cancel()
	resp, err := n.ListConversations(ctx, &gmproto.ListConversationsRequest{Count: 50, Folder: gmproto.ListConversationsRequest_INBOX})
	if err != nil || !c.current(gen) {
		return "", c.fail("LOAD_FAILED", "Could not load conversations. Check the phone connection.")
	}
	views := make([]conversationView, 0, 50)
	for _, v := range resp.GetConversations() {
		if len(views) == 50 {
			break
		}
		if v != nil {
			views = append(views, viewConversation(v))
		}
	}
	return encode(map[string]any{"conversations": views, "limit": 50}), nil
}

// Messages reads recent messages without marking them read.
func (c *Client) Messages(conversationID string) (string, error) {
	c.opMu.Lock()
	defer c.opMu.Unlock()
	if !validID(conversationID) {
		return "", c.fail("INVALID_CONVERSATION", "Select an existing conversation.")
	}
	n, base, gen, err := c.connection()
	if err != nil {
		return "", c.fail("NOT_READY", "Wait for the linked phone to be ready.")
	}
	ctx, cancel := context.WithTimeout(base, operationTimeout)
	defer cancel()
	conv, err := n.GetConversation(ctx, conversationID)
	if err != nil || conv == nil {
		return "", c.fail("LOAD_FAILED", "Could not load this conversation.")
	}
	resp, err := n.FetchMessages(ctx, conversationID, 50, nil)
	if err != nil || !c.current(gen) {
		return "", c.fail("LOAD_FAILED", "Could not load messages. Check the phone connection.")
	}
	views := make([]messageView, 0, 50)
	for _, m := range resp.GetMessages() {
		if len(views) == 50 {
			break
		}
		if m != nil {
			v := viewMessage(m, true)
			views = append(views, v)
			c.observe(v)
		}
	}
	sort.SliceStable(views, func(i, j int) bool { return views[i].TimestampMS < views[j].TimestampMS })
	return encode(map[string]any{"conversation": viewConversation(conv), "messages": views, "limit": 50}), nil
}
func validID(v string) bool { return v != "" && len(v) <= 512 && !strings.ContainsAny(v, "\r\n\x00") }

// Send performs one explicit RCS-only plain-text attempt in an existing
// conversation using the chosen linked-phone SIM. Reusing a request ID never
// resends it. A fresh conversation check precedes each new attempt.
func (c *Client) Send(conversationID, simID, text, requestID string) (string, error) {
	c.opMu.Lock()
	defer c.opMu.Unlock()
	if !validID(conversationID) || !validID(simID) || strings.TrimSpace(text) == "" || len(text) > 16*1024 || !utf8.ValidString(text) {
		return "", c.fail("INVALID_SEND", "Choose a conversation and SIM, and enter up to 16 KB of text.")
	}
	if _, err := uuid.Parse(requestID); err != nil {
		return "", c.fail("INVALID_REQUEST_ID", "A new send requires a valid request ID.")
	}
	h := sha256.Sum256([]byte(encode([]string{conversationID, simID, text})))
	hash := hex.EncodeToString(h[:])
	c.mu.Lock()
	previous, exists := c.journal[requestID]
	blocked := c.storageFailed
	for _, e := range c.journal {
		blocked = blocked || blocksSend(e)
	}
	full := len(c.journal) >= maxJournal
	c.mu.Unlock()
	if exists {
		if previous.Hash != hash {
			return "", c.fail("REQUEST_ID_REUSED", "This request ID already belongs to another message.")
		}
		return encode(previous.sendView), nil
	}
	if blocked {
		return "", c.fail("SEND_UNCERTAIN", "An earlier send is still pending or unknown. Refresh its conversation and check Google Messages before sending again.")
	}
	if full {
		return "", c.fail("JOURNAL_FULL", "This test has reached its 256-attempt limit. Export test observations before resetting pairing.")
	}
	n, base, gen, err := c.connection()
	if err != nil {
		return "", c.fail("NOT_READY", "Wait for the linked phone to be ready.")
	}
	ctx, cancel := context.WithTimeout(base, operationTimeout)
	defer cancel()
	conv, err := n.GetConversation(ctx, conversationID)
	if err != nil {
		return "", c.fail("RCS_CHECK_FAILED", "Could not recheck this conversation. Nothing was sent.")
	}
	if conv == nil || conv.GetConversationID() != conversationID || conv.GetType() != gmproto.ConversationType_RCS || conv.GetSendMode() != gmproto.ConversationSendMode_SEND_MODE_AUTO || conv.GetReadOnly() || conv.GetStatus() != gmproto.ConversationStatus_ACTIVE || conversationName(conv) == "" {
		return "", c.fail("RCS_REQUIRED", "This conversation is not currently an active RCS conversation. Nothing was sent.")
	}
	defaultApp, err := n.IsBugleDefault(ctx)
	if err != nil || !defaultApp.GetSuccess() {
		return "", c.fail("GOOGLE_MESSAGES_REQUIRED", "Keep Google Messages as the phone's default SMS app for this RCS test.")
	}
	c.mu.Lock()
	sim := c.sims[simID]
	if sim != nil {
		sim = proto.Clone(sim).(*gmproto.SIMCard)
	}
	c.mu.Unlock()
	if sim == nil || !viewSIM(sim).Active || !sim.GetRCSChats().GetEnabled() {
		return "", c.fail("SIM_RCS_UNAVAILABLE", "Select a known active SIM with RCS enabled. Nothing was sent.")
	}
	if !c.current(gen) || ctx.Err() != nil {
		return "", c.fail("CANCELLED", "Send cancelled before submission.")
	}
	entry := journalEntry{sendView: sendView{RequestID: requestID, ConversationID: conversationID, SIMID: simID, Status: "submitting", Protocol: "unconfirmed", Message: "Submitting one RCS-only attempt."}, Hash: hash}
	c.mu.Lock()
	c.journal[requestID] = entry
	c.mu.Unlock()
	if !c.persist() {
		c.mu.Lock()
		delete(c.journal, requestID)
		c.mu.Unlock()
		return "", c.fail("STORAGE_FAILED", "Could not save the send record securely. Nothing was sent.")
	}
	if !c.current(gen) || ctx.Err() != nil {
		return c.finishSend(requestID, "rejected", "Cancelled before submission."), nil
	}
	payload := &gmproto.SendMessageRequest{ConversationID: conversationID, TmpID: requestID, ForceRCS: true, SIMPayload: sim.GetSIMData().GetSIMPayload(), MessagePayload: &gmproto.MessagePayload{TmpID: requestID, TmpID2: requestID, ConversationID: conversationID, ParticipantID: simID, MessageInfo: []*gmproto.MessageInfo{{Data: &gmproto.MessageInfo_MessageContent{MessageContent: &gmproto.MessageContent{Content: text}}}}}}
	resp, err := n.SendMessage(ctx, payload)
	if err != nil {
		return c.finishSend(requestID, "unknown", "The request ended without a reliable result. Check this conversation in Google Messages; do not resend."), nil
	}
	switch resp.GetStatus() {
	case gmproto.SendMessageResponse_SUCCESS:
		return c.finishSend(requestID, "accepted", "Accepted by the phone bridge; waiting for the actual message protocol and status."), nil
	case gmproto.SendMessageResponse_FAILURE_2, gmproto.SendMessageResponse_FAILURE_3, gmproto.SendMessageResponse_FAILURE_4:
		return c.finishSend(requestID, "rejected", "Google Messages did not accept this attempt."), nil
	default:
		return c.finishSend(requestID, "unknown", "Google Messages returned an unrecognized result. Check the conversation before sending again."), nil
	}
}
func (c *Client) finishSend(id, status, message string) string {
	c.mu.Lock()
	e := c.journal[id]
	// A live message observation may arrive before the request completes.
	if e.Status == "submitting" {
		e.Status = status
		e.Message = message
		c.journal[id] = e
	}
	c.mu.Unlock()
	if !c.persist() {
		c.fail("STORAGE_FAILED", "The latest send status could not be saved. Check Google Messages before another attempt.")
	}
	c.emit(map[string]any{"type": "send", "requestId": e.RequestID, "status": e.Status, "protocol": e.Protocol, "message": e.Message})
	return encode(e.sendView)
}
func (c *Client) observe(v messageView) {
	if v.RequestID == "" || !v.Outgoing {
		return
	}
	c.mu.Lock()
	e, ok := c.journal[v.RequestID]
	if !ok || e.ConversationID != v.ConversationID {
		c.mu.Unlock()
		return
	}
	status := observedStatus(v.StatusCode)
	// Delayed history updates must not downgrade delivered/read to sent.
	rank := map[string]int{"sent": 1, "delivered": 2, "read": 3}
	if rank[e.Status] > rank[status] && rank[e.Status] > 0 {
		status = e.Status
	}
	e.Status = status
	e.Protocol = v.Protocol
	e.Message = "Observed message protocol: " + v.Protocol + "."
	if v.SIMID != "" {
		e.ActualSIMID = v.SIMID
	}
	if v.Protocol == "SMS" || v.Protocol == "MMS" {
		e.Mismatch = true
		e.Message = "Protocol mismatch: the phone reported " + v.Protocol + " for an RCS-only request. Stop the test and inspect Google Messages."
	}
	if e.ActualSIMID != "" && e.ActualSIMID != e.SIMID {
		e.Mismatch = true
		e.Message = "SIM mismatch: the phone reported a different sender SIM. Stop the test and inspect Google Messages."
	}
	c.journal[v.RequestID] = e
	c.mu.Unlock()
	if !c.persist() {
		c.fail("STORAGE_FAILED", "Could not save an observed message status. Check Google Messages before sending again.")
	}
	c.emit(map[string]any{"type": "send", "requestId": e.RequestID, "status": e.Status, "protocol": e.Protocol, "message": e.Message, "simId": e.SIMID, "actualSimId": e.ActualSIMID})
}

func (c *Client) handleEvent(q queuedEvent) {
	c.eventMu.Lock()
	defer c.eventMu.Unlock()
	if !c.current(q.generation) {
		return
	}
	switch e := q.value.(type) {
	case *events.ClientReady:
		c.mu.Lock()
		paired := validAuth(c.auth)
		c.mu.Unlock()
		if paired {
			c.setState("ready", "Linked phone is ready.")
		}
	case *events.AuthTokenRefreshed:
		c.mu.Lock()
		paired := validAuth(c.auth)
		c.mu.Unlock()
		if paired && !c.persist() {
			c.setState("error", "Secure session storage failed.")
			c.fail("STORAGE_FAILED", "Could not save renewed pairing keys. Reconnect after checking storage.")
		}
	case *events.GaiaLoggedOut:
		c.setState("reauth_required", "Google Messages signed out this linked device.")
	case *events.ListenFatalError:
		c.setState("error", "The linked-device connection stopped.")
		c.fail("CONNECTION_FAILED", "The Google Messages connection stopped. Reconnect or renew pairing.")
	case *events.ListenTemporaryError, *events.PhoneNotResponding, *events.PingFailed:
		c.setState("offline", "Waiting for the linked phone to reconnect.")
	case *events.ListenRecovered, *events.PhoneRespondingAgain:
		c.setState("connecting", "Phone connection recovered; refresh or reconnect if it does not become ready.")
	case *gmproto.Settings:
		c.mu.Lock()
		if e.SIMCards != nil {
			c.sims = make(map[string]*gmproto.SIMCard)
			for _, s := range e.SIMCards {
				if s != nil && s.GetSIMParticipant().GetID() != "" {
					c.sims[s.GetSIMParticipant().GetID()] = proto.Clone(s).(*gmproto.SIMCard)
				}
			}
		}
		views := make([]simView, 0, len(c.sims))
		for _, s := range c.sims {
			views = append(views, viewSIM(s))
		}
		c.mu.Unlock()
		sort.Slice(views, func(i, j int) bool { return views[i].ID < views[j].ID })
		c.emit(map[string]any{"type": "settings", "sims": views})
	case *gmproto.Conversation:
		c.emit(map[string]any{"type": "conversation", "conversation": viewConversation(e)})
	case *libgm.WrappedMessage:
		if e.Message != nil {
			v := viewMessage(e.Message, e.IsOld)
			c.observe(v)
			c.emit(map[string]any{"type": "message", "message": v})
		}
	}
}
