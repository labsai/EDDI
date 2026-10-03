# Embedding the Chat UI

> The token hand-off protocol and `eddi.chat.img-sources` are newer than 6.5.0. Under 6.5.0 a framed widget with authentication on could not start a conversation, and agent output images from other hosts were blocked.

EDDI serves a chat widget at `/chat`. Another site can show it in an `<iframe>`. This page is the contract between that host page and the widget: who may frame it, how a signed-in user's token gets into it with authentication on, and which images it may load.

## 1. Allow the host page to frame `/chat`

By default `/chat` answers with `Content-Security-Policy: frame-ancestors 'none'`, so every browser refuses to render it inside someone else's page. List the origins allowed to frame it, separated by spaces:

```properties
# application.properties, or EDDI_CHAT_FRAME_ANCESTORS in the environment
eddi.chat.frame-ancestors=https://portal.example.com
```

This is the operator's allow-list, and the browser enforces it before the widget runs. A page that is not listed cannot frame the widget, so it cannot talk to it either. Only `/chat` takes this setting. The Manager and the API always refuse to be framed. Keep the list exact: a wildcard such as `https://*.example.org` lets every subdomain frame the widget and hand it a token.

```html
<iframe
  id="eddi-chat"
  src="https://eddi.example.com/chat/production/<agentId>?tokenOrigin=https%3A%2F%2Fportal.example.com"
  style="width: 400px; height: 600px; border: none;"
></iframe>
```

The display parameters (`theme`, `title`, `hideUndo`, the colour parameters) are listed in the [Chat UI README](../ui/chat/README.md). `theme` accepts only `dark`, `light` or `system`. Every colour parameter must be a plain colour: hex, `rgb()`/`hsl()` with numeric arguments, or a named colour. `fontFamily` must be a plain font list. The widget ignores any other value and logs a warning in the console.

## 2. Hand the widget a token (authentication on)

With OIDC enabled, every API call the widget makes needs a bearer token. The widget has no login flow of its own; the host page, which has already signed the user in, passes it an access token by `postMessage`.

### The protocol

| Direction | Message | When |
| --- | --- | --- |
| widget → host | `{ type: "eddi-chat-ready", protocol: 1 }` | Once, as soon as the widget is listening |
| widget → host | `{ type: "eddi-chat-token-request", protocol: 1 }` | About a minute before the current token expires, and whenever the backend answers `401` |
| host → widget | `{ type: "eddi-chat-token", token: "<access token>" }` | In answer to either message, and at any other time the host has a newer token |

Rules the widget enforces:

- **Opt-in, exact origins.** The widget only takes part when its URL names the host with `?tokenOrigin=`, as a bare origin (`https://portal.example.com`) or several separated by commas. The widget drops a wildcard, a path, the opaque origin `null` and any scheme other than `http`/`https`. Without `tokenOrigin` the widget accepts no token and posts nothing.
- **Never a `*` target.** Every message the widget sends is posted with an allow-listed origin as its `targetOrigin`. If the parent is at any other origin, the browser discards the message.
- **Only the parent, only from an allow-listed origin.** A token is accepted only when `event.source` is the widget's own parent window and `event.origin` is in the list. A message from a sibling frame, a popup or any other origin is ignored. The token must be printable ASCII without whitespace, at most 16 KB.
- **Refresh.** If the token is a JWT, the widget reads its `exp` claim, only to schedule the next request, and asks for a new token a minute before it expires; for a token shorter-lived than five minutes it asks at about 80 % of its life. The backend still verifies every token.
- **401 retry.** When a request is answered `401`, the widget asks for a new token, waits up to ten seconds, and sends the request once more. The same applies to a request that went out before the first token arrived. If no token comes, the `401` is shown as usual.
- **Memory only.** The token is held in a JavaScript variable. It is never written to the URL, `localStorage`, `sessionStorage` or a cookie.

### Host page example

```html
<script>
  const CHAT_ORIGIN = "https://eddi.example.com";
  const frame = document.getElementById("eddi-chat");

  // getAccessToken() is your application's: for keycloak-js, something like
  //   await keycloak.updateToken(30); return keycloak.token;
  async function sendToken() {
    const token = await getAccessToken();
    frame.contentWindow.postMessage({ type: "eddi-chat-token", token }, CHAT_ORIGIN);
  }

  window.addEventListener("message", (event) => {
    // Answer only the widget: the right origin AND our own iframe.
    if (event.origin !== CHAT_ORIGIN || event.source !== frame.contentWindow) return;
    const type = event.data && event.data.type;
    if (type === "eddi-chat-ready" || type === "eddi-chat-token-request") sendToken();
  });
</script>
```

The host must also post to an exact origin (`CHAT_ORIGIN`), never `"*"`: a `*` target would hand the token to whatever document the iframe happens to be showing.

### Which token to send

Send an access token issued for EDDI. That means its audience and roles must satisfy the backend (`eddi-user` is enough to chat; see [security.md](security.md)). The widget acts as that user, and conversation ownership is checked against the token's subject. Use a short-lived token. The refresh protocol exists so that a long lifetime is never needed.

### Other ways to pass a token

- `?token=` in the iframe URL still works and is stripped from the address bar on load, but a crafted link can sign the widget in as somebody else (login CSRF), and the token passes through the host page's markup. Prefer the hand-off above.
- A page that bundles the widget itself can set `ChatConfig.authToken`.

## 3. Images in agent output

An agent designer can put an image in an agent's output: an output item `{ "type": "image", "uri": "https://cdn.example.com/a.png", "alt": "…" }`. The widget renders these as `<img>`, for `http(s)` URLs and same-origin paths only.

Under the widget's original policy, `img-src 'self' data: blob:`, the browser refused every image hosted anywhere but EDDI itself. The `/chat` policy now appends `eddi.chat.img-sources`:

| Setting | Effect |
| --- | --- |
| `https:` (default) | Images from any https host are shown |
| `https://cdn.example.com https://img.example.org` | Only those hosts |
| `'self'` | None beyond EDDI itself (the old behaviour). An empty value falls back to the default |

**The trade-off.** Markdown images in model-written text are never rendered as `<img>`: the widget turns them into links, so a model cannot make the browser fetch a URL of its choosing, whatever this setting says. What `https:` gives up is `img-src` as a second line of defence. If some other flaw let markup into the page, an injected `<img>` could send data to any https host. If you know where your designers' images live, list those hosts. The setting applies to `/chat` only. The Manager and Swagger UI keep their own `img-src`.
