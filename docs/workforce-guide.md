# Workforce: a guide for end users

Workforce (`/workforce`) is where a signed-in person uses the agents and teams their organisation
offers them. It is a separate screen from the Manager (`/manage`), which is for building and
administration. A person who holds only the `eddi-user` role lands in Workforce straight away; an
editor or administrator chooses between the two at `/welcome`. A deployment can give Workforce its
own name in its [branding](white-labeling.md).

This page describes what you see in Workforce as a user. How an agent or team gets into Workforce
in the first place is in [End-User Catalog](catalog.md).

---

## Home

Home is the first screen. From top to bottom:

1. **A box to type in.** Pick an agent from the list under the box, type your question and press
   **Enter** (or the arrow button). **Shift+Enter** starts a new line. The list starts on the agent
   of your most recent chat, or on the first agent offered to you. Sending opens a new chat with
   that agent, and your question is its first message.
2. **Suggestions.** If the agent's designer wrote conversation starters, they appear as buttons
   under the box. Clicking one starts a new chat with that text.
3. **A notice about review**, above the box, when the agent's maintainers may read conversations.
   It is shown before you send anything.
4. **Recent**: your five most recent chats and team sessions. Click one to continue it.
5. **Agents and teams**: everything you may use, as cards. An agent's card shows the name, picture
   and colour its designer chose. Click an agent to open a chat with it, or a team to open its board.

If Home shows nothing to pick, no agent or team has been offered to you yet. Ask your
administrator. Agents are offered one by one; see [Listing is opt-in](catalog.md#listing-is-opt-in).

---

## Your conversations (the sidebar)

The sidebar lists your own conversations: **chats** (with one agent) and **team sessions** (a
discussion of a team). Nobody else's conversations appear here, not even for an administrator.

They are grouped by when you last used them:

| Group | Holds |
| --- | --- |
| **Pinned** | Chats you pinned, always on top |
| **Today**, **Yesterday** | By calendar day |
| **Previous 7 days**, **Previous 30 days** | The weeks before |
| One group per month | Anything older, newest month first |

**Titles.** A chat is named after your first message in it, shortened. Some agents ask a model to
write the title instead; your administrator decides that per agent. A chat that has no title yet
shows the agent's name and the date. A team session is named after the question it discusses.
Secret input (a password or key the agent asked for) never becomes a title.

Point at a chat (or move to it with the keyboard) to see its actions:

- **Rename** turns the title into a text box. Press **Enter** to save, **Esc** to keep the old one.
  A title you set is never replaced by an automatic one. Titles can be up to 200 characters.
- **Pin** keeps the chat at the top; **Unpin** puts it back into its date group.
- **Delete** asks first, then removes the conversation from your list.

Team sessions can be deleted, but not renamed or pinned. Each member of a team talks in a
conversation of its own; those are part of the team session and are not listed as separate chats.

Every change shows at once. If the server refuses it (you are offline, or the conversation was
deleted elsewhere), the list goes back to how it was and a message says why.

---

## A chat

The chat page shows the conversation with one agent. Its address names the agent and the
conversation, so:

- **reloading the page** brings you back to the same conversation, and
- **opening the address in a second tab**, or bookmarking it, opens the same conversation.

A link to your conversation does not let somebody else read it: the server checks who owns it.
(Administrators, and reviewers of an agent whose review notice you were shown, can read it.)

The agent's designer can switch off some controls for their agent: undo and redo, attachments,
ratings, quick-reply buttons and streaming (the reply appearing word by word). When a control is
missing, that is the agent's setting, not a fault.

---

## A team

A team's board shows its members, its discussions and, while one runs, the discussion as it
happens. Start a discussion by typing your question on the board. The sidebar lists it under
**Your conversations** from then on. See [Group Conversations](group-conversations.md) for how
the discussion styles work.

---

## For administrators

- An agent or team appears in Workforce only when it is deployed, the person may use it, and it is
  **listed** ([End-User Catalog](catalog.md)).
- An agent's name, picture, colour and starters come from its `appearance`, and the controls from
  its `conversationControls` ([Agent presentation](agent-config-authoring.md#agent-presentation-appearance-conversationcontrols)).
  A picture hosted on another site shows only if the Manager's content security policy allows that
  host (`img-src`); a small `data:image` picture always shows.
- Titles, renaming and pinning are stored on the conversation and are covered by GDPR export and
  erasure ([GDPR / CCPA Compliance](gdpr-compliance.md)).
