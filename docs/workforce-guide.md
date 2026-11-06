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
   that agent, and your question is its first message. The list offers your **teams** too, below
   the agents: with a team picked, sending opens the team's board and starts a new discussion with
   your question.
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

### Why an answer came the way it did

Sometimes a line under an answer explains how it was reached, in plain words:

- **"Answered by a larger model because the first answer wasn't confident enough."** The agent's
  designer set it up to try a fast model first and to hand the question to a stronger one when
  needed. The line also says when the first model took too long, failed, or gave an answer that
  could not be used.
- **"The model was asked again because its first reply was cut off."** (or was empty, was stopped
  by the provider's content filter, was not in the expected format, or the conversation was too
  long for it).
- **"A safety check removed part of the answer (…)."** A guardrail the designer configured acted on
  your message or on the answer: it stopped it, removed part of it, flagged it, or sent the answer
  back to be rewritten. The name in brackets says which check it was.

These lines appear only when the server reported such a step while you watched the answer arrive.
Like the tool cards, they are not kept: after a reload they are gone. Nothing is shown for things
the server does not report, such as a spending limit that ended a cascade early.

### Handing a question to another agent or team (@)

Type **@** in the message box to hand your question to another agent or to a team instead of the
agent of this chat. A list of the agents and teams offered to you appears; keep typing to narrow
it, pick one with **↑ ↓** and **Enter** (or **Tab**), or click it. **Esc** closes the list. The
choice shows above the box ("Hand over to …"); type your question and send it. **Backspace** in an
empty box, or the **×**, takes the choice back.

Sending opens a new chat with that agent, or a new discussion of that team, with your question as
its first message. The chat you came from gets a line saying where the question went, with a link
to it, and the new conversation says where it came from, with a link back. These two lines are
kept **in this browser only**: on another device, or after signing out, the conversations are still
there but the lines are not. The agent or team you hand over to sees your question, not the chat it
came from. Files you attached are not handed over.

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

## Keyboard

| Keys | What they do |
| --- | --- |
| **⌘K** / **Ctrl+K** | Go to a conversation or an agent: type a few letters, **↑ ↓** to pick, **Enter** to open. Also finds older chats by title |
| **⌘⇧O** / **Ctrl+Shift+O** | New chat (Home, with the message box ready) |
| **Enter** · **Shift+Enter** | Send · new line |
| **↑** (in an empty message box) | Edit your last message |
| **@** · **↑ ↓** · **Enter** or **Tab** · **Esc** | Hand the question to another agent or team: open the list · pick · choose · close the list |
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
happens. Start a discussion by typing your question on the board, from Home with the team picked,
or by handing a chat question to the team with **@**. The sidebar lists it under **Your
conversations** from then on. See [Group Conversations](group-conversations.md) for how the
discussion styles work.

- **The decision, in plain words.** When the team votes, a judge rules, or the members agree, the
  decision card starts with one sentence that says what happened, for example "The team voted, and
  “Ship it” won. Members who disagreed: 1." The tally, the ballots and what the members who
  disagreed said follow below it. With a screen reader the sentence is read out when the decision
  arrives.
- **Your turn.** A team can include people as members. When it is a person's turn, the discussion
  waits, and the board shows what they are asked and a box to answer. If it is your turn, the box
  says "It's your turn"; if it is somebody else's, it says whose turn it is and that only they, or
  an administrator, can answer. The team's designer may give the turn a time limit, shown beside
  it; when it runs out, the turn is skipped or the discussion stops.
- **Shared artifacts.** Some teams write documents together while they discuss, such as a plan, a
  draft or a table. When a discussion has any, a button with a document icon and their number
  appears at the top of the board. It opens a panel (the whole screen on a phone) with the
  documents: open one to read it, and pick an earlier **version** from the list to see what it said
  before, with who wrote it and when. The server keeps the ten most recent earlier versions of each
  document; the panel says when older ones are gone. You can read the documents but not change
  them: the team's members write them.

A team's preset **templates** (research pod, decision board, …) are for the people who build teams:
creating a team from one needs the editor role. As a user, you start from the teams offered to you,
which your organisation built, often from those templates.

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
- **Hand-offs with @** offer the entries of the end-user catalog (`GET /catalog/agents`,
  `GET /catalog/groups`), so only what the person may use and what is listed. A hand-off starts the
  target conversation the ordinary way, with the question as its first message; the server has no
  record that it came from another chat. EDDI's own hand-off concept, [Cross-Team
  Handoff](team-handoff.md), is something else: an agent filing a task into a standing team's
  backlog under rules both teams' authors set. The marker lines are kept in the browser's storage,
  under the signed-in user, and removed at sign-out.
- **The lines under an answer** come from three stream events: `cascade_escalation` (its
  `reason`), `llm_retry` (its `reason`) and `guardrail_triggered` (`type`, `phase`, `action`). Only
  those are explained. A cascade's `maxCostPerRun` or `maxTotalDurationMs` stopping escalation early
  sends no event, so nothing says so; neither the model's name nor a cost is shown to users. Like
  the tool cards, the lines are not restored from the stored conversation.
- **Tool cards** come from the `tool_result` events of a streamed turn: the tool's name, whether it
  worked, and a summary the server redacts like the `task_complete` tool trace and cuts to 200
  characters. Workforce shows the summary as plain text, never as markup. The cards are not stored:
  the stored trace is not redacted for clients, so a conversation read back from the server shows
  none. A turn the agent answers without streaming (the agent's `conversationControls.streaming` is
  off, or the user switched streaming off in the Manager) has no cards and no tool warning.
