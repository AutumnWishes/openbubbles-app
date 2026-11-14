// SPDX-License-Identifier: AGPL-3.0-or-later
package bridge

import (
	"encoding/json"
	"sort"
	"strings"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

type simView struct {
	ID         string `json:"id"`
	Label      string `json:"label"`
	Number     string `json:"number"`
	RCSEnabled bool   `json:"rcsEnabled"`
	Active     bool   `json:"active"`
}
type conversationView struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Preview  string `json:"preview"`
	RCS      bool   `json:"rcs"`
	SendMode string `json:"sendMode"`
	SIMID    string `json:"simId"`
	ReadOnly bool   `json:"readOnly"`
	Unread   bool   `json:"unread"`
}
type messageView struct {
	ID             string `json:"id"`
	ConversationID string `json:"conversationId"`
	Text           string `json:"text"`
	TimestampMS    int64  `json:"timestampMs"`
	Outgoing       bool   `json:"outgoing"`
	Protocol       string `json:"protocol"`
	Status         string `json:"status"`
	StatusCode     int32  `json:"statusCode"`
	RequestID      string `json:"requestId"`
	Old            bool   `json:"old"`
	HasMedia       bool   `json:"hasMedia"`
	SIMID          string `json:"simId"`
}
type sendView struct {
	RequestID      string `json:"requestId"`
	ConversationID string `json:"conversationId"`
	SIMID          string `json:"simId"`
	Status         string `json:"status"`
	Protocol       string `json:"protocol"`
	Message        string `json:"message"`
	ActualSIMID    string `json:"actualSimId,omitempty"`
}
type journalEntry struct {
	sendView
	Hash     string `json:"hash"`
	Mismatch bool   `json:"mismatch,omitempty"`
}

func encode(v any) string { b, _ := json.Marshal(v); return string(b) }
func viewSIM(s *gmproto.SIMCard) simView {
	d := s.GetSIMData()
	number := d.GetFormattedPhoneNumber()
	if number == "" {
		number = d.GetInternationalPhoneNumber()
	}
	label := strings.TrimSpace(d.GetCarrierName() + " " + number)
	if label == "" {
		label = "SIM " + s.GetSIMParticipant().GetID()
	}
	return simView{s.GetSIMParticipant().GetID(), label, number, s.GetRCSChats().GetEnabled(), d != nil && d.GetInt1() != -1 && d.GetSIMPayload() != nil}
}
func viewConversation(c *gmproto.Conversation) conversationView {
	name := conversationName(c)
	return conversationView{c.GetConversationID(), name, c.GetLatestMessage().GetDisplayContent(), c.GetType() == gmproto.ConversationType_RCS, strings.TrimPrefix(c.GetSendMode().String(), "SEND_MODE_"), c.GetDefaultOutgoingID(), c.GetReadOnly() || name == "", c.GetUnread()}
}
func conversationName(c *gmproto.Conversation) string {
	if name := strings.TrimSpace(c.GetName()); name != "" {
		return name
	}
	var names []string
	for _, p := range c.GetParticipants() {
		if p.GetIsMe() {
			continue
		}
		name := strings.TrimSpace(p.GetFullName())
		number := strings.TrimSpace(p.GetFormattedNumber())
		if number == "" {
			number = strings.TrimSpace(p.GetID().GetNumber())
		}
		if name != "" && number != "" {
			name += " (" + number + ")"
		} else if name == "" {
			name = number
		}
		if name != "" {
			names = append(names, name)
		}
		if len(names) == 20 {
			break
		}
	}
	return strings.Join(names, ", ")
}
func viewMessage(m *gmproto.Message, old bool) messageView {
	v := messageView{ID: m.GetMessageID(), ConversationID: m.GetConversationID(), TimestampMS: m.GetTimestamp() / 1000, RequestID: m.GetTmpID(), Old: old, Protocol: "unknown", StatusCode: int32(m.GetMessageStatus().GetStatus())}
	var parts []string
	for _, info := range m.GetMessageInfo() {
		if text := info.GetMessageContent(); text != nil {
			parts = append(parts, text.GetContent())
		}
		if info.GetMediaContent() != nil || info.GetLocationContent() != nil {
			v.HasMedia = true
		}
	}
	v.Text = strings.Join(parts, "\n")
	switch m.GetType() {
	case 1:
		v.Protocol = "SMS"
	case 2, 3:
		v.Protocol = "MMS"
	case 4:
		v.Protocol = "RCS"
	}
	v.Outgoing = v.StatusCode > 0 && v.StatusCode < 100
	if v.Outgoing {
		v.SIMID = m.GetParticipantID()
		if v.SIMID == "" {
			v.SIMID = m.GetSenderParticipant().GetID().GetParticipantID()
		}
	}
	v.Status = m.GetMessageStatus().GetStatus().String()
	return v
}
func observedStatus(code int32) string {
	switch code {
	case 1:
		return "sent"
	case 2:
		return "delivered"
	case 11:
		return "read"
	case 8, 9, 12, 13, 17, 18, 19, 21, 22, 23, 24, 25, 26, 27:
		return "rejected"
	default:
		return "accepted"
	}
}
func uncertain(status string) bool {
	return status == "submitting" || status == "accepted" || status == "unknown"
}
func blocksSend(e journalEntry) bool {
	return e.Mismatch || uncertain(e.Status) || e.Protocol == "SMS" || e.Protocol == "MMS" || (e.Status != "rejected" && e.Protocol != "RCS") || (e.ActualSIMID != "" && e.ActualSIMID != e.SIMID)
}
func sortSends(s []sendView) {
	sort.Slice(s, func(i, j int) bool { return s[i].RequestID < s[j].RequestID })
}
