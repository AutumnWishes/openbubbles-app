// SPDX-License-Identifier: AGPL-3.0-or-later
package bridge

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/events"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

type memoryListener struct {
	mu     sync.Mutex
	saved  string
	events []string
	fail   bool
	saves  int
}

func (l *memoryListener) OnEvent(v string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.events = append(l.events, v)
}
func (l *memoryListener) OnSession(v string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.fail {
		return false
	}
	l.saved = v
	l.saves++
	return true
}
func (l *memoryListener) session() string { l.mu.Lock(); defer l.mu.Unlock(); return l.saved }

// This transport has no network capability. Every test starts with an already
// ready synthetic phone, and no cookie import/pair operation contacts Google.
type fakeWire struct {
	conv         *gmproto.Conversation
	defaultApp   bool
	err          error
	sendErr      error
	response     *gmproto.SendMessageResponse
	sends        []*gmproto.SendMessageRequest
	onSend       func(*gmproto.SendMessageRequest)
	messages     []*gmproto.Message
	disconnected bool
	onDefault    func(context.Context) (*gmproto.IsBugleDefaultResponse, error)
	gets         int
	reads        [][2]string
	readErr      error
	onFetch      func()
	onRead       func()
}

func (f *fakeWire) ListConversations(context.Context, *gmproto.ListConversationsRequest) (*gmproto.ListConversationsResponse, error) {
	return &gmproto.ListConversationsResponse{Conversations: []*gmproto.Conversation{f.conv}}, f.err
}
func (f *fakeWire) GetConversation(context.Context, string) (*gmproto.Conversation, error) {
	f.gets++
	return f.conv, f.err
}
func (f *fakeWire) FetchMessages(context.Context, string, int64, *gmproto.Cursor) (*gmproto.ListMessagesResponse, error) {
	if f.onFetch != nil {
		f.onFetch()
	}
	return &gmproto.ListMessagesResponse{Messages: f.messages}, f.err
}
func (f *fakeWire) SendMessage(_ context.Context, r *gmproto.SendMessageRequest) (*gmproto.SendMessageResponse, error) {
	f.sends = append(f.sends, r)
	if f.onSend != nil {
		f.onSend(r)
	}
	return f.response, f.sendErr
}
func (f *fakeWire) MarkRead(_ context.Context, conversationID, messageID string) error {
	f.reads = append(f.reads, [2]string{conversationID, messageID})
	if f.onRead != nil {
		f.onRead()
	}
	return f.readErr
}
func (f *fakeWire) IsBugleDefault(ctx context.Context) (*gmproto.IsBugleDefaultResponse, error) {
	if f.onDefault != nil {
		return f.onDefault(ctx)
	}
	return &gmproto.IsBugleDefaultResponse{Success: f.defaultApp}, f.err
}
func (f *fakeWire) Disconnect() { f.disconnected = true }

func testAuth() *libgm.AuthData {
	a := libgm.NewAuthData()
	a.Browser = &gmproto.Device{}
	a.Mobile = &gmproto.Device{}
	a.DestRegID = uuid.New()
	a.Cookies = map[string]string{"SID": "test-secret-never-show"}
	return a
}
func fixture(t *testing.T) (*Client, *fakeWire, *memoryListener) {
	t.Helper()
	l := &memoryListener{}
	c, err := NewClient("", l)
	if err != nil {
		t.Fatal(err)
	}
	f := &fakeWire{conv: &gmproto.Conversation{ConversationID: "conversation", Name: "Test recipient", Type: gmproto.ConversationType_RCS, Status: gmproto.ConversationStatus_ACTIVE, DefaultOutgoingID: "personal"}, defaultApp: true, response: &gmproto.SendMessageResponse{Status: gmproto.SendMessageResponse_SUCCESS}}
	c.auth = testAuth()
	c.network = f
	c.ready = true
	c.state = "ready"
	c.ctx, c.cancel = context.WithCancel(context.Background())
	for i, id := range []string{"personal", "work"} {
		c.sims[id] = &gmproto.SIMCard{SIMParticipant: &gmproto.SIMParticipant{ID: id}, RCSChats: &gmproto.RCSChats{Enabled: true}, SIMData: &gmproto.SIMData{Int1: int64(i), SIMPayload: &gmproto.SIMPayload{SIMNumber: int32(i + 1)}}}
	}
	t.Cleanup(c.Disconnect)
	return c, f, l
}
func result(t *testing.T, s string) sendView {
	t.Helper()
	var v sendView
	if err := json.Unmarshal([]byte(s), &v); err != nil {
		t.Fatal(err)
	}
	return v
}

func TestSendExplicitSIMForceRCSAndPersistBeforeNetwork(t *testing.T) {
	for _, sim := range []string{"personal", "work"} {
		t.Run(sim, func(t *testing.T) {
			c, f, l := fixture(t)
			id := uuid.NewString()
			f.onSend = func(req *gmproto.SendMessageRequest) {
				var saved sessionData
				if err := json.Unmarshal([]byte(l.session()), &saved); err != nil {
					t.Fatal(err)
				}
				if saved.Journal[id].Status != "submitting" {
					t.Fatal("send attempted before durable submission record")
				}
				if !req.ForceRCS || req.GetMessagePayload().GetParticipantID() != sim || req.GetSIMPayload().GetSIMNumber() != c.sims[sim].GetSIMData().GetSIMPayload().GetSIMNumber() {
					t.Fatal("RCS or explicit SIM routing lost")
				}
				if req.TmpID != id || req.MessagePayload.TmpID != id || req.MessagePayload.TmpID2 != id {
					t.Fatal("stable request ID not used throughout payload")
				}
			}
			body, err := c.Send("conversation", sim, "hello", id)
			if err != nil {
				t.Fatal(err)
			}
			v := result(t, body)
			if v.Status != "accepted" || v.Protocol != "unconfirmed" {
				t.Fatalf("HTTP response falsely reported delivery: %+v", v)
			}
			if len(f.sends) != 1 {
				t.Fatal("unexpected send count")
			}
		})
	}
}
func TestGuardFailuresNeverSend(t *testing.T) {
	cases := map[string]func(*Client, *fakeWire, *memoryListener){
		"SMS conversation": func(c *Client, f *fakeWire, l *memoryListener) { f.conv.Type = gmproto.ConversationType_SMS },
		"latched SMS": func(c *Client, f *fakeWire, l *memoryListener) {
			f.conv.SendMode = gmproto.ConversationSendMode_SEND_MODE_XMS_LATCH
		},
		"read only":                   func(c *Client, f *fakeWire, l *memoryListener) { f.conv.ReadOnly = true },
		"deleted":                     func(c *Client, f *fakeWire, l *memoryListener) { f.conv.Status = gmproto.ConversationStatus_DELETED },
		"wrong conversation":          func(c *Client, f *fakeWire, l *memoryListener) { f.conv.ConversationID = "different" },
		"RCS disabled":                func(c *Client, f *fakeWire, l *memoryListener) { c.sims["personal"].RCSChats.Enabled = false },
		"inactive SIM":                func(c *Client, f *fakeWire, l *memoryListener) { c.sims["personal"].SIMData.Int1 = -1 },
		"unknown SIM":                 func(c *Client, f *fakeWire, l *memoryListener) { delete(c.sims, "personal") },
		"Google Messages not default": func(c *Client, f *fakeWire, l *memoryListener) { f.defaultApp = false },
		"storage failure":             func(c *Client, f *fakeWire, l *memoryListener) { l.fail = true },
		"stale connection":            func(c *Client, f *fakeWire, l *memoryListener) { c.cancel() },
		"fresh lookup failed":         func(c *Client, f *fakeWire, l *memoryListener) { f.err = errors.New("SECRET SERVER BODY") },
	}
	for name, setup := range cases {
		t.Run(name, func(t *testing.T) {
			c, f, l := fixture(t)
			setup(c, f, l)
			_, err := c.Send("conversation", "personal", "hello", uuid.NewString())
			if err == nil {
				t.Fatal("guard did not reject")
			}
			if len(f.sends) != 0 {
				t.Fatal("send escaped guard")
			}
			if strings.Contains(err.Error(), "SECRET") {
				t.Fatal("raw server error escaped")
			}
		})
	}
}
func TestUnknownSendCannotRetryEvenAfterRestart(t *testing.T) {
	c, f, l := fixture(t)
	id := uuid.NewString()
	f.sendErr = errors.New("private server response")
	first, err := c.Send("conversation", "personal", "hello", id)
	if err != nil {
		t.Fatal(err)
	}
	if v := result(t, first); v.Status != "unknown" {
		t.Fatalf("got %+v", v)
	}
	again, err := c.Send("conversation", "personal", "hello", id)
	if err != nil || again != first || len(f.sends) != 1 {
		t.Fatal("same request was retried")
	}
	if _, err = c.Send("conversation", "personal", "other", id); err == nil {
		t.Fatal("request ID collision accepted")
	}
	if _, err = c.Send("conversation", "personal", "hello", uuid.NewString()); err == nil {
		t.Fatal("fresh ID bypassed unresolved send")
	}
	restored, err := NewClient(l.session(), &memoryListener{})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(restored.Snapshot(), `"hasUncertainSend":true`) {
		t.Fatal("restart lost pending safety block")
	}
	if _, err = restored.Send("conversation", "personal", "hello", id); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(l.session(), "hello") {
		t.Fatal("journal persisted message body")
	}
}
func TestInterruptedSubmissionRestoresAsUnknown(t *testing.T) {
	c, _, l := fixture(t)
	id := uuid.NewString()
	c.journal[id] = journalEntry{sendView: sendView{RequestID: id, Status: "submitting", ConversationID: "conversation", SIMID: "personal", Protocol: "unconfirmed"}, Hash: strings.Repeat("a", 64)}
	if !c.persist() {
		t.Fatal("persist")
	}
	r, err := NewClient(l.session(), &memoryListener{})
	if err != nil {
		t.Fatal(err)
	}
	if r.journal[id].Status != "unknown" {
		t.Fatal("interrupted attempt could be repeated")
	}
}
func TestObservedMessageWinsOverHTTPResponse(t *testing.T) {
	c, f, _ := fixture(t)
	id := uuid.NewString()
	f.onSend = func(*gmproto.SendMessageRequest) {
		c.observe(messageView{RequestID: id, ConversationID: "conversation", Outgoing: true, StatusCode: 2, Protocol: "RCS"})
	}
	value, err := c.Send("conversation", "personal", "hello", id)
	if err != nil {
		t.Fatal(err)
	}
	v := result(t, value)
	if v.Status != "delivered" || v.Protocol != "RCS" {
		t.Fatalf("protocol observation overwritten: %+v", v)
	}
	c.observe(messageView{RequestID: id, ConversationID: "conversation", Outgoing: true, StatusCode: 1, Protocol: "RCS"})
	if c.journal[id].Status != "delivered" {
		t.Fatal("old sent event downgraded delivered")
	}
}
func TestMessagesResolvePendingAndPreserveActualProtocol(t *testing.T) {
	c, f, _ := fixture(t)
	id := uuid.NewString()
	if _, err := c.Send("conversation", "personal", "hello", id); err != nil {
		t.Fatal(err)
	}
	f.messages = []*gmproto.Message{{MessageID: "message", TmpID: id, ConversationID: "conversation", Timestamp: 1234567000, Type: 1, MessageStatus: &gmproto.MessageStatus{Status: gmproto.MessageStatusType_OUTGOING_COMPLETE}}}
	body, err := c.Messages("conversation")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(body, `"protocol":"SMS"`) || !strings.Contains(body, `"timestampMs":1234567`) {
		t.Fatal("actual protocol or microsecond timestamp misrepresented")
	}
	if e := c.journal[id]; e.Protocol != "SMS" || !strings.Contains(e.Message, "mismatch") {
		t.Fatal("SMS fallback concealed")
	}
}
func TestSnapshotAndErrorsNeverExposeAuthentication(t *testing.T) {
	c, _, l := fixture(t)
	if strings.Contains(c.Snapshot(), "test-secret") || strings.Contains(c.Snapshot(), "Cookies") {
		t.Fatal("snapshot leaked auth")
	}
	c.handleEvent(queuedEvent{c.generation, &gmproto.Settings{}})
	for _, e := range l.events {
		if strings.Contains(e, "test-secret") {
			t.Fatal("event leaked auth")
		}
	}
	if len(c.sims) != 2 {
		t.Fatal("partial settings update deleted known SIMs")
	}
}
func TestCookieValidation(t *testing.T) {
	valid := `{"SID":"a","HSID":"b","SSID":"c","APISID":"d","SAPISID":"e","OSID":"f"}`
	if _, err := parseCookies(valid); err != nil {
		t.Fatal(err)
	}
	for _, bad := range []string{`{}`, `[]`, `{"SID":"secret\nheader"}`, strings.Replace(valid, `"SID":"a"`, `"SID":"a; injected"`, 1), strings.Replace(valid, `"SID":"a"`, `"unexpected":"a"`, 1)} {
		if _, err := parseCookies(bad); err == nil {
			t.Fatal("invalid cookies accepted")
		}
	}
}
func TestForgetClearsLocalSecretsAndJournal(t *testing.T) {
	c, f, l := fixture(t)
	if !c.persist() {
		t.Fatal("persist")
	}
	if err := c.Forget(); err != nil {
		t.Fatal(err)
	}
	if l.session() != "" || c.auth != nil || len(c.journal) != 0 || !f.disconnected {
		t.Fatal("forget retained session")
	}
}

func TestUnknownAndNilSendResponseRemainBlocked(t *testing.T) {
	for _, response := range []*gmproto.SendMessageResponse{nil, {Status: gmproto.SendMessageResponse_UNKNOWN}, {Status: gmproto.SendMessageResponse_Status(91)}} {
		c, f, _ := fixture(t)
		f.response = response
		body, err := c.Send("conversation", "personal", "hello", uuid.NewString())
		if err != nil {
			t.Fatal(err)
		}
		if result(t, body).Status != "unknown" {
			t.Fatal("unknown response treated as rejection")
		}
		if _, err = c.Send("conversation", "personal", "again", uuid.NewString()); err == nil {
			t.Fatal("unknown response allowed new attempt")
		}
	}
}
func TestProtocolAndSIMMismatchRemainLatched(t *testing.T) {
	for _, test := range []struct{ protocol, sim string }{{"SMS", "personal"}, {"RCS", "work"}} {
		c, _, l := fixture(t)
		id := uuid.NewString()
		if _, err := c.Send("conversation", "personal", "hello", id); err != nil {
			t.Fatal(err)
		}
		c.observe(messageView{RequestID: id, ConversationID: "conversation", Outgoing: true, StatusCode: 1, Protocol: test.protocol, SIMID: test.sim})
		// Even a later contradictory normal report cannot silently clear it.
		c.observe(messageView{RequestID: id, ConversationID: "conversation", Outgoing: true, StatusCode: 2, Protocol: "RCS", SIMID: "personal"})
		if _, err := c.Send("conversation", "personal", "next", uuid.NewString()); err == nil {
			t.Fatal("mismatch did not block")
		}
		r, err := NewClient(l.session(), &memoryListener{})
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(r.Snapshot(), `"hasUncertainSend":true`) {
			t.Fatal("mismatch block lost at restart")
		}
	}
}
func TestStaleEventsCannotRestoreDisconnectedState(t *testing.T) {
	c, _, _ := fixture(t)
	old := c.generation
	c.Disconnect()
	c.handleEvent(queuedEvent{old, &events.ClientReady{}})
	c.handleEvent(queuedEvent{old, &gmproto.Settings{SIMCards: []*gmproto.SIMCard{{SIMParticipant: &gmproto.SIMParticipant{ID: "stale"}}}}})
	if c.ready || c.state != "disconnected" || c.sims["stale"] != nil {
		t.Fatal("stale event mutated current connection")
	}
}
func TestForgetReleasesSecretsEvenIfStorageRejectsDelete(t *testing.T) {
	c, _, l := fixture(t)
	l.fail = true
	if err := c.Forget(); err == nil {
		t.Fatal("failed deletion was hidden")
	}
	if c.auth != nil || c.network != nil || c.ready || len(c.journal) != 0 {
		t.Fatal("forgotten client retained secrets")
	}
}
func TestPartialAuthIsNeverReady(t *testing.T) {
	c, _, _ := fixture(t)
	c.auth = nil
	c.ready = false
	c.handleEvent(queuedEvent{c.generation, &events.ClientReady{}})
	if c.ready {
		t.Fatal("pairing event made incomplete auth ready")
	}
}
func TestDestinationFallbackAndUnknownRecipientGuard(t *testing.T) {
	c, f, _ := fixture(t)
	f.conv.Name = ""
	f.conv.Participants = []*gmproto.Participant{{IsMe: true, FullName: "Me"}, {ID: &gmproto.SmallInfo{Number: "+15550101234"}}}
	if viewConversation(f.conv).Name != "+15550101234" {
		t.Fatal("missing recipient label")
	}
	f.conv.Participants = nil
	if !viewConversation(f.conv).ReadOnly {
		t.Fatal("unidentifiable recipient remained sendable")
	}
	if _, err := c.Send("conversation", "personal", "hello", uuid.NewString()); err == nil {
		t.Fatal("unknown recipient send accepted")
	}
	if len(f.sends) > 0 {
		t.Fatal("unknown recipient send escaped")
	}
}
func TestPinnedPairingPatchRejectsAmbiguousPhoneBeforeSelection(t *testing.T) {
	// This source assertion catches a missing/changed staged patch before an
	// APK is produced, including its cancellation path. It performs no pairing.
	b, err := os.ReadFile("../build/upstream/pkg/libgm/pair_google.go")
	if err != nil {
		t.Fatal(err)
	}
	source := string(b)
	guard := `} else if len(primaryDevices) > 1 {` + "\n\t\t" + `return "", nil, errors.New("rcs prototype requires exactly one Google Messages phone")`
	if !strings.Contains(source, guard) {
		t.Fatal("upstream silently selects among multiple phones")
	}
	if !strings.Contains(source, "case <-ctx.Done():\n\t\treturn \"\", nil, ctx.Err()") || strings.Contains(source, "longPollConnectWait.Wait()") {
		t.Fatal("pairing long-poll startup does not honor cancellation")
	}
}

func waitState(t *testing.T, c *Client, state string) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		c.mu.Lock()
		got := c.state
		c.mu.Unlock()
		if got == state {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("did not reach %s: %s", state, c.Snapshot())
}

func TestSettingsVerifiesPhoneWithoutClientReadyEvent(t *testing.T) {
	c, _, _ := fixture(t)
	c.setState("connecting", "test")
	c.handleEvent(queuedEvent{c.generation, &gmproto.Settings{}})
	waitState(t, c, "ready")
	if _, err := c.Conversations(); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(c.Snapshot(), `"readinessCheck":"confirmed"`) {
		t.Fatal("missing probe evidence")
	}
}

func TestRecoveryRechecksPhoneAndAllowsRefresh(t *testing.T) {
	for _, recovery := range []any{&events.ListenRecovered{}, &events.PhoneRespondingAgain{}} {
		c, _, _ := fixture(t)
		c.handleEvent(queuedEvent{c.generation, &events.PhoneNotResponding{}})
		if _, err := c.Conversations(); err == nil {
			t.Fatal("offline refresh allowed")
		}
		c.handleEvent(queuedEvent{c.generation, recovery})
		waitState(t, c, "ready")
		if _, err := c.Conversations(); err != nil {
			t.Fatal(err)
		}
		// Redundant recovery notices must not downgrade an already-ready phone.
		c.handleEvent(queuedEvent{c.generation, recovery})
		if !strings.Contains(c.Snapshot(), `"ready":true`) {
			t.Fatal("recovery downgraded ready")
		}
	}
}

func TestReadinessRequiresSuccessfulDefaultAppResponse(t *testing.T) {
	for _, response := range []*gmproto.IsBugleDefaultResponse{nil, {Success: false}} {
		c, f, _ := fixture(t)
		c.setState("connecting", "test")
		f.onDefault = func(context.Context) (*gmproto.IsBugleDefaultResponse, error) { return response, nil }
		c.handleEvent(queuedEvent{c.generation, &gmproto.Settings{}})
		waitState(t, c, "offline")
		if _, err := c.Conversations(); err == nil {
			t.Fatal("false readiness")
		}
	}
	c, f, l := fixture(t)
	c.setState("connecting", "test")
	f.onDefault = func(context.Context) (*gmproto.IsBugleDefaultResponse, error) {
		return nil, errors.New("SECRET response")
	}
	c.handleEvent(queuedEvent{c.generation, &gmproto.Settings{}})
	waitState(t, c, "offline")
	c.eventMu.Lock()
	defer c.eventMu.Unlock()
	l.mu.Lock()
	defer l.mu.Unlock()
	if strings.Contains(strings.Join(l.events, ""), "SECRET") {
		t.Fatal("raw readiness error leaked")
	}
}

func TestDelayedReadinessCannotUndoDisconnectOrNewFailure(t *testing.T) {
	for _, change := range []string{"disconnect", "outage", "logout", "fatal"} {
		t.Run(change, func(t *testing.T) {
			c, f, _ := fixture(t)
			c.setState("connecting", "test")
			started, release, finished := make(chan struct{}), make(chan struct{}), make(chan struct{})
			f.onDefault = func(context.Context) (*gmproto.IsBugleDefaultResponse, error) {
				close(started)
				<-release
				defer close(finished)
				return &gmproto.IsBugleDefaultResponse{Success: true}, nil
			}
			c.handleEvent(queuedEvent{c.generation, &gmproto.Settings{}})
			<-started
			want := "offline"
			switch change {
			case "disconnect":
				c.Disconnect()
				want = "disconnected"
			case "outage":
				c.handleEvent(queuedEvent{c.generation, &events.PhoneNotResponding{}})
			case "logout":
				c.handleEvent(queuedEvent{c.generation, &events.GaiaLoggedOut{}})
				want = "reauth_required"
			case "fatal":
				c.handleEvent(queuedEvent{c.generation, &events.ListenFatalError{}})
				want = "error"
			}
			close(release)
			<-finished
			// Take the event lock after the RPC returns, then allow its completion turn.
			time.Sleep(10 * time.Millisecond)
			c.mu.Lock()
			defer c.mu.Unlock()
			if c.ready || c.state != want {
				t.Fatal("stale check restored readiness")
			}
		})
	}
}

// Counts effects, not rendered history, so a fetch/observe/refresh feedback loop
// cannot pass merely because it returns the same messages to the caller.
func (l *memoryListener) sendEffects(t *testing.T) (events, saves int) {
	t.Helper()
	l.mu.Lock()
	defer l.mu.Unlock()
	for _, raw := range l.events {
		var event struct {
			Type string `json:"type"`
		}
		if err := json.Unmarshal([]byte(raw), &event); err != nil {
			t.Fatal(err)
		}
		if event.Type == "send" {
			events++
		}
	}
	return events, l.saves
}

func TestRepeatedHistoryDoesNotTriggerAnotherSendRefresh(t *testing.T) {
	c, f, l := fixture(t)
	id := uuid.NewString()
	if _, err := c.Send("conversation", "personal", "hello", id); err != nil {
		t.Fatal(err)
	}
	message := &gmproto.Message{MessageID: "message", TmpID: id, ConversationID: "conversation",
		ParticipantID: "personal", Timestamp: 1234567000, Type: 4,
		MessageStatus: &gmproto.MessageStatus{Status: gmproto.MessageStatusType_OUTGOING_DELIVERED}}
	f.messages = []*gmproto.Message{message}
	beforeEvents, beforeSaves := l.sendEffects(t)
	first, err := c.Messages("conversation")
	if err != nil {
		t.Fatal(err)
	}
	events, saves := l.sendEffects(t)
	if events != beforeEvents+1 || saves != beforeSaves+1 || c.journal[id].Status != "delivered" {
		t.Fatal("first delivery observation was not published and saved exactly once")
	}
	for i := 0; i < 3; i++ {
		next, err := c.Messages("conversation")
		if err != nil || first != next {
			t.Fatal("repeat history changed unexpectedly", err)
		}
		nextEvents, nextSaves := l.sendEffects(t)
		if nextEvents != events || nextSaves != saves {
			t.Fatal("unchanged history retriggered refresh or storage write")
		}
	}
	// A real read receipt must still update the saved journal and notify Android.
	message.MessageStatus.Status = gmproto.MessageStatusType_OUTGOING_DISPLAYED
	if _, err := c.Messages("conversation"); err != nil {
		t.Fatal(err)
	}
	nextEvents, nextSaves := l.sendEffects(t)
	if nextEvents != events+1 || nextSaves != saves+1 || c.journal[id].Status != "read" {
		t.Fatal("genuine read receipt was suppressed")
	}
	if _, err := c.Messages("conversation"); err != nil {
		t.Fatal(err)
	}
	finalEvents, finalSaves := l.sendEffects(t)
	if finalEvents != nextEvents || finalSaves != nextSaves {
		t.Fatal("read receipt history restarted the refresh loop")
	}
}

func TestDuplicateObservationsPreserveRoutingSafety(t *testing.T) {
	for _, mismatch := range []struct{ protocol, sim string }{{"SMS", "personal"}, {"RCS", "work"}} {
		c, _, l := fixture(t)
		id := uuid.NewString()
		if _, err := c.Send("conversation", "personal", "hello", id); err != nil {
			t.Fatal(err)
		}
		observed := messageView{RequestID: id, ConversationID: "conversation", Outgoing: true,
			StatusCode: 2, Protocol: "RCS", SIMID: "personal"}
		c.observe(observed)
		events, saves := l.sendEffects(t)
		// An older status at the same route must not cause a false change.
		observed.StatusCode = 1
		c.observe(observed)
		nextEvents, nextSaves := l.sendEffects(t)
		if nextEvents != events || nextSaves != saves {
			t.Fatal("older status retriggered a send event")
		}
		observed.Protocol, observed.SIMID = mismatch.protocol, mismatch.sim
		c.observe(observed)
		nextEvents, nextSaves = l.sendEffects(t)
		if nextEvents != events+1 || nextSaves != saves+1 || !c.journal[id].Mismatch {
			t.Fatal("real protocol/SIM mismatch was suppressed")
		}
		c.observe(observed)
		finalEvents, finalSaves := l.sendEffects(t)
		if finalEvents != nextEvents || finalSaves != nextSaves {
			t.Fatal("duplicate mismatch was republished")
		}
		if _, err := c.Send("conversation", "personal", "next", uuid.NewString()); err == nil {
			t.Fatal("idempotence cleared the routing safety block")
		}
	}
}

func TestSendEventsIdentifyConversation(t *testing.T) {
	c, _, l := fixture(t)
	id := uuid.NewString()
	if _, err := c.Send("conversation", "personal", "hello", id); err != nil {
		t.Fatal(err)
	}
	c.observe(messageView{RequestID: id, ConversationID: "conversation", Outgoing: true, StatusCode: 2, Protocol: "RCS", SIMID: "personal"})
	l.mu.Lock()
	defer l.mu.Unlock()
	count := 0
	for _, raw := range l.events {
		var event map[string]any
		if err := json.Unmarshal([]byte(raw), &event); err != nil {
			t.Fatal(err)
		}
		if event["type"] == "send" {
			count++
			if event["conversationId"] != "conversation" {
				t.Fatal("send event cannot be matched to the visible conversation")
			}
		}
	}
	if count != 2 {
		t.Fatalf("expected one initial send result and one delivery observation, got %d", count)
	}
}
