# Workforce: a guide for end users

Workforce (`/workforce`) is where a signed-in person uses the agents and teams their organisation
offers them. It is a separate app from the Manager (`/manage`), which is for building and
administration. A person who holds only the `eddi-user` role lands in Workforce straight away; an
editor or administrator chooses between the two at `/welcome`. A deployment can give Workforce its
own name in its [branding](white-labeling.md).

Workforce loads only what it needs, so it opens faster than the Manager. Both apps share one
sign-in: moving from one to the other (the **Manager** and **Workforce** links, an administrator's
**Platform Operator** link) reloads the page, and you stay signed in. Your theme, language and
pinned items carry over.

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

### Searching your conversations

The box at the top of the list searches **the titles** of all your chats, not only the ones the
list shows. It does not search what was said in them. Type a few letters (upper or lower case does
not matter): the results replace the list until you clear the box (**Esc** or the **×**). **↑ ↓**
pick a result and **Enter** opens it. When there are more results than fit, **More results** shows
the next ones. The search finds your own chats only, an administrator's too. Team sessions are not
searched; find them in the list or with **⌘K**.

**⌘K** / **Ctrl+K** searches the same titles: below the matches from the list, it adds older chats
whose title matches.

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

### While the agent answers

- The reply appears as it is written, and the page follows it. **Scroll up** to re-read something
  and the page stops following; the **↓** button takes you back to the latest message and following
  starts again. Sending a message always brings you back down.
- **Stop** (the square in place of the send button, or **Esc**) ends the reply. Stopping takes back
  the rest of the reply, never what the agent already did: a booking an agent's tool made stays
  made. A moment later Workforce checks that the stop took. On a server that runs on several
  machines the answer may already have been running elsewhere; then a line in the conversation says
  so: the reply "finished anyway" (and is shown in full), or it is "still running" and appears when
  you open the conversation again.
- If the connection drops while a reply comes in, the conversation says **Reconnecting…** and
  checks with the server for about half a minute. The agent usually keeps working meanwhile; when
  the reply is ready it replaces the part you saw. Only if the server cannot be reached does the
  reply end with "Connection lost".

### What the agent's tools did

When an agent uses tools to answer (a search, a lookup, sending an email), a line under your
message says so: **Tools used: 2**, and how many failed. Click it to see a card per tool: its name,
whether it worked, and the start of what it returned (at most 200 characters, with keys and
passwords removed by the server). It is closed until you open it. The cards show what happened
while you watched: they are not kept with the conversation, so after a reload, or for a
conversation you open from the list, they are gone. A team member's conversation (from a team's
board) shows them too.

### Changing what you asked

- **Edit** (under your last message, or **↑** in an empty message box) takes your last message and
  the agent's answer back and puts your words into the message box to change and send again.
- **Regenerate** (under the agent's last answer) asks the agent to answer the same message again.

Both appear only on the last turn, and only if the agent's designer allows undo (and, for
Regenerate, redo). Neither takes back what a tool already did. When the last turn used tools, a
warning beside both buttons names them (for example "Ran send_email. Editing takes back the reply,
not what the tools did."): the email stays sent, and a new answer may send another. After an edit
the same warning appears once more as a message. A secret message, or one that carried files,
cannot be edited: send it again instead.

### What a reply can contain

Tables, code (with its language named, and a copy button), formulas written between `$$` signs,
and diagrams: a code block marked `mermaid` is drawn as a diagram. Every reply has a **Copy**
button. A picture an agent's designer configured is shown; a link to a picture in the agent's own
words stays a link, so a reply cannot make your browser fetch anything you did not click.

### When the agent asks for something specific, or you type a secret

The message box has three modes, and shows one at a time:

- **The field an agent asked for.** An agent can ask for one value, such as an API key or an email
  address, and the message box becomes that field, with the agent's label. A password or key is
  hidden as you type (the eye shows it), sent exactly as typed, and kept out of the conversation's
  title and its stored history. An email or text field is an ordinary message. **Type a message
  instead** goes back to the normal message box.
- **Secret mode.** The lock button hides what you type and sends it as a secret: the conversation
  shows dots, and the server masks it in stored history. Turning the lock off throws the hidden text
  away instead of showing it. Whatever you had typed in the normal box before is still there. A
  secret is never kept as a draft. Quick-reply buttons are hidden while the lock is on.
- **The normal message box**, with the agent's quick-reply buttons above it when it offers some.

### Files

Attach files with the paper clip, by pasting (a screenshot, for example) or by dropping them on the
chat; up to five per message. Each file shows its own progress while it uploads. If one fails, it
stays in the message box with the reason and a **Try again** button; only the files that uploaded
are sent with your message.

### Rating a reply

When the agent asks for ratings, each answer has a thumbs up and a thumbs down. After a thumbs
down you can pick a reason and add a comment; both are optional, and the thumb counts on its own.
Who sees your rating is said under the rating card.

---

## What your agents remember about you

Some agents remember things about you between conversations: your preferred language, your time
zone, a project you mentioned. **My memory**, at the bottom of the sidebar (or **⌘K** / **Ctrl+K**
and "remember"), lists everything they have saved about you, newest first. For each entry it shows:

- **which agent saved it.** If an older entry does not record its agent, the page says so.
- **who may use it.** *Private* means only the agent that saved it uses it. *Team* means the agents
  of a team use it, and the page names the team. *All agents* means any agent you talk to uses it.
- when it last changed.

Search filters the list by any of these, including the agent's and the team's names.

**Forget** deletes one entry. **Forget everything** deletes them all, for every agent and team,
after asking you to confirm. Either way your agents no longer know it in new conversations, and it
cannot be undone. Your conversations themselves are not deleted. You can forget any entry, including
a shared one; forgetting a shared entry removes it for every agent that uses it.

Records your administrator keeps for data-protection requests (for example a restriction of
processing) are not listed here and cannot be deleted from this page. Ask your administrator for a
full erasure of your data. With sign-in switched off on a deployment, there is no person to remember,
and the page says so. Nobody else can open your memory from this page: the server only shows it to
you, or to an administrator.

---

## Keyboard

| Keys | What they do |
| --- | --- |
| **⌘K** / **Ctrl+K** | Go to a conversation or an agent: type a few letters, **↑ ↓** to pick, **Enter** to open. Also finds older chats by title |
| **⌘⇧O** / **Ctrl+Shift+O** | New chat (Home, with the message box ready) |
| **Enter** · **Shift+Enter** | Send · new line |
| **↑** (in an empty message box) | Edit your last message |
| **Esc** | Stop the reply being written; close a dialog |
| **?** | The list of shortcuts |

With a screen reader, each finished reply is read once, with who said it, rather than word by word
while it is written. After you send a message, start a new chat or close a dialog, the cursor goes
back to the message box.

---

## On your phone, or as an app

Workforce works on a phone: the bottom bar replaces the sidebar, and the message box stays above
the on-screen keyboard. You can also **install** it: in Chrome or Edge, *Install app* in the address
bar or the menu; in Safari on iPhone, *Share → Add to Home Screen*. It then opens in its own window
with your deployment's name and icon. Installing changes nothing about signing in, and nothing is
kept on the device beyond what a browser tab keeps: Workforce does not work offline.

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
- Workforce uses the Manager's Keycloak client (both pages load `/manage/__auth_config__.js`), so
  it needs no client or redirect URI of its own. EDDI serves its page for every `/workforce/...`
  address, which is why a reload or a bookmarked chat opens where it should; behind a reverse proxy,
  pass `/workforce/` through to EDDI like `/manage/`. Building EDDI with `-DskipUi=true` leaves it
  out, with the other UIs.
- The Platform Operator stays in the Manager. Workforce shows an administrator a link to it,
  never the operator itself.
- **Installing Workforce.** EDDI serves its web app manifest at `/workforce/manifest.webmanifest`,
  rendered from the current [branding](white-labeling.md): the product name, the primary colour and
  the brand's icon (`/branding/icon`, the EDDI icon when the brand has none). Upload a square PNG of
  at least 512 pixels, or an SVG, as the icon for a sharp home-screen icon. There is deliberately no
  service worker: browsers do not need one to install an app, and a cache in front of signed-in
  conversations is a risk with nothing to gain, since Workforce needs the server anyway. Behind a
  reverse proxy the manifest travels with the rest of `/workforce/`.
- **Stopping a reply in a cluster.** Stop calls `POST /agents/{conversationId}/cancel`, which stops a
  running turn only on the node running it ([HITL](hitl.md)). Workforce reads the conversation again
  1.5 and 5 seconds later and tells the user when the turn ran on, so nobody believes a reply was
  stopped that was not.
- **Search** uses `GET /conversationstore/conversations/search` ([REST API reference](rest-api-reference.md#searching-my-conversations)):
  the caller's own conversations, by title only, administrators included. It keeps no index of its
  own, so GDPR erasure removes what it can find with the conversations.
- **Tool cards** come from the `tool_result` events of a streamed turn: the tool's name, whether it
  worked, and a summary the server redacts like the `task_complete` tool trace and cuts to 200
  characters. Workforce shows the summary as plain text, never as markup. The cards are not stored:
  the stored trace is not redacted for clients, so a conversation read back from the server shows
  none. A turn the agent answers without streaming (the agent's `conversationControls.streaming` is
  off, or the user switched streaming off in the Manager) has no cards and no tool warning.
