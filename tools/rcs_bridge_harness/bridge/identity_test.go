// SPDX-License-Identifier: AGPL-3.0-or-later
package bridge

import (
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
	"testing"
)

func TestConversationIdentityUsesProtocolAddressesNotDisplayNames(t *testing.T) {
	c := &gmproto.Conversation{ConversationID: "chat", Name: "+19999999999", LastMessageTimestamp: 1700000000123000,
		Participants: []*gmproto.Participant{
			{ID: &gmproto.SmallInfo{Number: "+15550000001"}, FullName: "Misleading contact"},
			{ID: &gmproto.SmallInfo{Number: "+15550000002"}, IsMe: true},
			{FullName: "+15550000003", FormattedNumber: "+15550000003"},
		}}
	v := viewConversation(c)
	if len(v.Addresses) != 1 || v.Addresses[0] != "+15550000001" {
		t.Fatalf("unverified peer identity: %#v", v.Addresses)
	}
	if len(v.LocalAddresses) != 1 || v.LocalAddresses[0] != "+15550000002" {
		t.Fatal("local identity was not kept separate")
	}
	if v.IsGroup || v.TimestampMS != 1700000000123 {
		t.Fatal("incorrect group/timestamp mapping")
	}
	c.IsGroupChat = true
	if !viewConversation(c).IsGroup {
		t.Fatal("explicit group identity lost")
	}
}
