// SPDX-License-Identifier: AGPL-3.0-or-later
package bridge

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"

	"github.com/google/uuid"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

func incomingMarker(id string) *gmproto.Message {
	return &gmproto.Message{MessageID: id, ConversationID: "conversation", Type: 4,
		MessageStatus: &gmproto.MessageStatus{Status: gmproto.MessageStatusType_INCOMING_COMPLETE}}
}

func TestMarkReadUsesExactNotificationMarker(t *testing.T) {
	c, f, _ := fixture(t)
	f.messages = []*gmproto.Message{incomingMarker("newer-message"), incomingMarker("notification-message")}
	if _, err := c.Messages("conversation"); err != nil {
		t.Fatal(err)
	}
	if len(f.reads) != 0 {
		t.Fatal("history loading marked messages read")
	}
	if err := c.MarkRead("conversation", "notification-message"); err != nil {
		t.Fatal(err)
	}
	if len(f.reads) != 1 || f.reads[0] != [2]string{"conversation", "notification-message"} {
		t.Fatal("mark read advanced beyond the notification marker")
	}
}

func TestMarkReadRejectsInvalidOrUnverifiedTarget(t *testing.T) {
	for name, mutate := range map[string]func(*Client, *fakeWire){
		"wrong conversation": func(_ *Client, f *fakeWire) { f.conv.ConversationID = "another-chat" },
		"not RCS":            func(_ *Client, f *fakeWire) { f.conv.Type = gmproto.ConversationType_SMS },
		"no marker":          func(_ *Client, f *fakeWire) { f.messages = []*gmproto.Message{incomingMarker("newer")} },
		"cross-chat marker":  func(_ *Client, f *fakeWire) { f.messages[0].ConversationID = "another-chat" },
		"outgoing marker": func(_ *Client, f *fakeWire) {
			f.messages[0].MessageStatus.Status = gmproto.MessageStatusType_OUTGOING_DELIVERED
		},
		"deleted marker": func(_ *Client, f *fakeWire) {
			f.messages[0].MessageStatus.Status = gmproto.MessageStatusType_INCOMING_DELETED
		},
		"SMS marker":              func(_ *Client, f *fakeWire) { f.messages[0].Type = 1 },
		"lookup failure":          func(_ *Client, f *fakeWire) { f.err = errors.New("private upstream failure") },
		"cancelled during lookup": func(c *Client, f *fakeWire) { f.onFetch = c.Disconnect },
	} {
		t.Run(name, func(t *testing.T) {
			c, f, _ := fixture(t)
			f.messages = []*gmproto.Message{incomingMarker("target")}
			mutate(c, f)
			err := c.MarkRead("conversation", "target")
			if err == nil || len(f.reads) != 0 || len(f.sends) != 0 {
				t.Fatal("unverified target reached a mutation")
			}
			if strings.Contains(err.Error(), "private upstream failure") {
				t.Fatal("upstream error leaked")
			}
		})
	}
	for _, input := range [][2]string{{"", "target"}, {"conversation", ""}, {"conversation", "bad\nmarker"}, {strings.Repeat("c", 513), "target"}} {
		c, f, _ := fixture(t)
		if err := c.MarkRead(input[0], input[1]); err == nil || f.gets != 0 || len(f.reads) != 0 {
			t.Fatal("invalid identifiers reached the transport")
		}
	}
}

func TestMarkReadUnconfirmedDoesNotRetry(t *testing.T) {
	for _, disconnect := range []bool{false, true} {
		c, f, _ := fixture(t)
		f.messages = []*gmproto.Message{incomingMarker("target")}
		if disconnect {
			f.onRead = c.Disconnect
		} else {
			f.readErr = errors.New("private upstream failure")
		}
		err := c.MarkRead("conversation", "target")
		if err == nil || !strings.Contains(err.Error(), "READ_UNCONFIRMED") || len(f.reads) != 1 {
			t.Fatal("uncertain receipt was retried or reported as confirmed")
		}
	}
}

func TestReplyUsesFreshConversationSIMAndDurableRCSRequest(t *testing.T) {
	for _, sim := range []string{"personal", "work"} {
		t.Run(sim, func(t *testing.T) {
			c, f, l := fixture(t)
			f.conv.DefaultOutgoingID = sim
			id := uuid.NewString()
			f.onSend = func(req *gmproto.SendMessageRequest) {
				var saved sessionData
				if err := json.Unmarshal([]byte(l.session()), &saved); err != nil {
					t.Fatal(err)
				}
				if saved.Journal[id].Status != "submitting" || saved.Journal[id].SIMID != sim {
					t.Fatal("reply not durably recorded before sending")
				}
				if !req.ForceRCS || req.GetMessagePayload().GetParticipantID() != sim || req.GetSIMPayload().GetSIMNumber() != c.sims[sim].GetSIMData().GetSIMPayload().GetSIMNumber() {
					t.Fatal("reply used a different SIM or lost ForceRCS")
				}
			}
			body, err := c.Reply("conversation", "quick reply", id)
			if err != nil {
				t.Fatal(err)
			}
			if got := result(t, body); got.SIMID != sim || got.Status != "accepted" || got.Protocol != "unconfirmed" {
				t.Fatal("incorrect reply result")
			}
			if len(f.sends) != 1 || f.gets != 1 {
				t.Fatal("reply did not use one fresh conversation check and one send")
			}
		})
	}
}

func TestReplyDuplicateKeepsRecordedRouteAcrossRestart(t *testing.T) {
	c, f, l := fixture(t)
	id := uuid.NewString()
	body, err := c.Reply("conversation", "reply once", id)
	if err != nil {
		t.Fatal(err)
	}
	f.conv.DefaultOutgoingID = "work"
	f.err = errors.New("must not look up a duplicate")
	duplicate, err := c.Reply("conversation", "reply once", id)
	if err != nil || duplicate != body || f.gets != 1 || len(f.sends) != 1 {
		t.Fatal("duplicate reply used new route or transmitted twice", err)
	}
	for _, input := range [][2]string{{"conversation", "different text"}, {"different chat", "reply once"}} {
		if _, err := c.Reply(input[0], input[1], id); err == nil {
			t.Fatal("reused reply ID accepted different content")
		}
	}
	restored, err := NewClient(l.session(), &memoryListener{})
	if err != nil {
		t.Fatal(err)
	}
	duplicate, err = restored.Reply("conversation", "reply once", id)
	if err != nil || duplicate != body {
		t.Fatal("restored journal lost reply deduplication", err)
	}
	if _, err := c.Reply("conversation", "another attempt", uuid.NewString()); err == nil || len(f.sends) != 1 {
		t.Fatal("pending reply did not block new attempt")
	}
}

func TestReplyRejectsMissingOrUnsafeRoute(t *testing.T) {
	for name, mutate := range map[string]func(*Client, *fakeWire, *memoryListener){
		"empty route":      func(_ *Client, f *fakeWire, _ *memoryListener) { f.conv.DefaultOutgoingID = "" },
		"unknown route":    func(_ *Client, f *fakeWire, _ *memoryListener) { f.conv.DefaultOutgoingID = "unknown" },
		"inactive route":   func(c *Client, _ *fakeWire, _ *memoryListener) { c.sims["personal"].SIMData.Int1 = -1 },
		"RCS disabled":     func(c *Client, _ *fakeWire, _ *memoryListener) { c.sims["personal"].RCSChats.Enabled = false },
		"SMS conversation": func(_ *Client, f *fakeWire, _ *memoryListener) { f.conv.Type = gmproto.ConversationType_SMS },
		"forced SMS": func(_ *Client, f *fakeWire, _ *memoryListener) {
			f.conv.SendMode = gmproto.ConversationSendMode_SEND_MODE_XMS
		},
		"read-only":           func(_ *Client, f *fakeWire, _ *memoryListener) { f.conv.ReadOnly = true },
		"not default SMS app": func(_ *Client, f *fakeWire, _ *memoryListener) { f.defaultApp = false },
		"storage fails":       func(_ *Client, _ *fakeWire, l *memoryListener) { l.fail = true },
		"disconnected":        func(c *Client, _ *fakeWire, _ *memoryListener) { c.Disconnect() },
	} {
		t.Run(name, func(t *testing.T) {
			c, f, l := fixture(t)
			mutate(c, f, l)
			if _, err := c.Reply("conversation", "quick reply", uuid.NewString()); err == nil || len(f.sends) != 0 {
				t.Fatal("unsafe reply reached transport")
			}
		})
	}
}
