import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { SecretKeyPicker } from "../secret-key-picker";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

describe("SecretKeyPicker", () => {
  const mockOnChange = vi.fn();

  beforeEach(() => {
    mockOnChange.mockReset();
    server.resetHandlers();

    // Default MSW handlers
    server.use(
      http.get("*/secretstore/secrets/health", () => {
        return HttpResponse.json({
          status: "UP",
          provider: "local",
          available: true,
        });
      }),
      http.get("*/secretstore/secrets/default", () => {
        return HttpResponse.json([
          {
            tenantId: "default",
            keyName: "openai-key",
            createdAt: "2026-06-08T12:00:00Z",
            lastAccessedAt: null,
            lastRotatedAt: null,
            checksum: "abc",
            description: "OpenAI API Key",
            allowedAgents: ["*"],
          },
          {
            tenantId: "default",
            keyName: "slack-token",
            createdAt: "2026-06-08T12:00:00Z",
            lastAccessedAt: null,
            lastRotatedAt: null,
            checksum: "def",
            description: "Slack Bot Token",
            allowedAgents: ["*"],
          },
        ]);
      }),
      http.put("*/secretstore/secrets/default/:keyName", async ({ params }) => {
        const { keyName } = params;
        return HttpResponse.json({
          reference: `\${vault:${keyName}}`,
          tenantId: "default",
          keyName,
        });
      })
    );
  });

  it("renders input field in direct password mode when value is not a vault reference", () => {
    renderWithProviders(
      <SecretKeyPicker value="super-secret-plain-text" onChange={mockOnChange} />
    );

    const input = screen.getByTestId("secret-key-picker-input") as HTMLInputElement;
    expect(input).toBeInTheDocument();
    expect(input.type).toBe("password");
    expect(input.value).toBe("super-secret-plain-text");

    const vaultBtn = screen.getByTestId("secret-key-picker-vault-btn");
    expect(vaultBtn).toBeInTheDocument();
  });

  it("toggles password visibility when the eye button is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<SecretKeyPicker value="plain" onChange={mockOnChange} />);

    const input = screen.getByTestId("secret-key-picker-input") as HTMLInputElement;
    expect(input.type).toBe("password");

    const toggleBtn = screen.getByRole("button", { name: "Show" });
    await user.click(toggleBtn);
    expect(input.type).toBe("text");

    const toggleBtnHide = screen.getByRole("button", { name: "Hide" });
    await user.click(toggleBtnHide);
    expect(input.type).toBe("password");
  });

  it("renders chip in vault mode when value is a vault reference", () => {
    renderWithProviders(
      <SecretKeyPicker value="${vault:openai-key}" onChange={mockOnChange} />
    );

    // Should display the vault key chip
    expect(screen.getByText("openai-key")).toBeInTheDocument();
    
    // Clear button should be present
    expect(screen.getByTestId("secret-key-picker-clear")).toBeInTheDocument();
  });

  /**
   * A reference copied out of the vault list, a config file or a chat message
   * arrives with whitespace around it. Without trimming, the field stayed a
   * masked password: the user could not tell the paste had landed as a
   * reference, and the backend received the raw paste.
   */
  it("recognises a pasted reference that carries whitespace", () => {
    renderWithProviders(
      <SecretKeyPicker value={"  ${vault:openai-key}  "} onChange={mockOnChange} />
    );

    expect(screen.getByText("openai-key")).toBeInTheDocument();
    expect(screen.getByTestId("secret-key-picker-clear")).toBeInTheDocument();
  });

  /**
   * isVaultRef also accepts the unbraced spellings. Gating normalisation on a
   * trailing "}" left those untrimmed: the chip rendered from the trimmed value
   * while the parent kept — and submitted — the raw paste.
   */
  it("normalises the unbraced and legacy reference forms too", async () => {
    const user = userEvent.setup();
    const { rerender } = renderWithProviders(
      <SecretKeyPicker value="" onChange={mockOnChange} />
    );

    await user.click(screen.getByTestId("secret-key-picker-input"));
    await user.paste("  vault:openai-key  ");
    expect(mockOnChange).toHaveBeenLastCalledWith("vault:openai-key");

    mockOnChange.mockReset();
    rerender(<SecretKeyPicker value="" onChange={mockOnChange} />);
    await user.click(screen.getByTestId("secret-key-picker-input"));
    await user.paste("  eddivault:legacy-key  ");
    expect(mockOnChange).toHaveBeenLastCalledWith("eddivault:legacy-key");
  });

  it("normalises a pasted reference before handing it up", async () => {
    const user = userEvent.setup();
    renderWithProviders(<SecretKeyPicker value="" onChange={mockOnChange} />);

    await user.click(screen.getByTestId("secret-key-picker-input"));
    await user.paste("  ${vault:openai-key}  ");

    expect(mockOnChange).toHaveBeenLastCalledWith("${vault:openai-key}");
  });

  it("shows warning icon if the vault reference key does not exist in secrets", async () => {
    renderWithProviders(
      <SecretKeyPicker value="${vault:non-existent-key}" onChange={mockOnChange} />
    );

    // Wait for secrets listing to load and check warning icon presence
    await waitFor(() => {
      expect(screen.getByTitle("This key was not found in the vault")).toBeInTheDocument();
    });
  });

  it("triggers onChange with empty string when clear button is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <SecretKeyPicker value="${vault:openai-key}" onChange={mockOnChange} />
    );

    const clearBtn = screen.getByTestId("secret-key-picker-clear");
    await user.click(clearBtn);

    expect(mockOnChange).toHaveBeenCalledWith("");
  });

  it("opens vault popup and displays list of secrets when vault button is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<SecretKeyPicker value="" onChange={mockOnChange} />);

    const vaultBtn = screen.getByTestId("secret-key-picker-vault-btn");
    await user.click(vaultBtn);

    // Should show popup and filter
    expect(screen.getByTestId("vault-popup")).toBeInTheDocument();
    
    // Wait for keys to load
    await screen.findByText("openai-key");
    await screen.findByText("slack-token");
    expect(screen.getByText("OpenAI API Key")).toBeInTheDocument();
  });

  it("filters secrets in the vault popup by keyName and description", async () => {
    const user = userEvent.setup();
    renderWithProviders(<SecretKeyPicker value="" onChange={mockOnChange} />);

    const vaultBtn = screen.getByTestId("secret-key-picker-vault-btn");
    await user.click(vaultBtn);

    // Search filter input
    const filterInput = screen.getByTestId("vault-popup-filter");
    await user.type(filterInput, "slack");

    // Only slack should remain
    expect(screen.queryByText("openai-key")).not.toBeInTheDocument();
    expect(screen.getByText("slack-token")).toBeInTheDocument();
  });

  it("selects a secret from the popup list and updates value to vault reference", async () => {
    const user = userEvent.setup();
    renderWithProviders(<SecretKeyPicker value="" onChange={mockOnChange} />);

    await user.click(screen.getByTestId("secret-key-picker-vault-btn"));
    const option = await screen.findByTestId("vault-key-openai-key");
    await user.click(option);

    expect(mockOnChange).toHaveBeenCalledWith("${vault:openai-key}");
  });

  it("opens create secret modal, handles inputs, and successfully calls store secret mutation", async () => {
    const user = userEvent.setup();
    let mutationPayload: Record<string, unknown> | null = null;

    server.use(
      http.put("*/secretstore/secrets/default/new-api-key", async ({ request }) => {
        mutationPayload = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({
          reference: "${vault:new-api-key}",
          tenantId: "default",
          keyName: "new-api-key",
        });
      })
    );

    renderWithProviders(<SecretKeyPicker value="" onChange={mockOnChange} />);

    // Open popup
    await user.click(screen.getByTestId("secret-key-picker-vault-btn"));
    
    // Click Create secret
    const createBtn = screen.getByTestId("vault-popup-create");
    await user.click(createBtn);

    // Dialog should be present
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(screen.getByText("Add Secret")).toBeInTheDocument();

    // Fill form
    const dialog = screen.getByRole("dialog");
    const inputs = dialog.querySelectorAll("input");
    expect(inputs).toHaveLength(3);
    await user.type(inputs[0]!, "new-api-key");
    await user.type(inputs[1]!, "superval");
    await user.type(inputs[2]!, "Test API Key");

    // Store
    const storeBtn = screen.getByRole("button", { name: "Store Secret" });
    await user.click(storeBtn);

    await waitFor(() => {
      expect(mutationPayload).not.toBeNull();
      expect(mutationPayload!.value).toBe("superval");
      expect(mutationPayload!.description).toBe("Test API Key");
      expect(mockOnChange).toHaveBeenCalledWith("${vault:new-api-key}");
    });
  });

  it("handles vault health down gracefully", async () => {
    userEvent.setup();
    server.use(
      http.get("*/secretstore/secrets/health", () => {
        return HttpResponse.json({
          status: "DOWN",
          provider: "local",
          available: false,
          reason: "Vault connection timeout",
        });
      })
    );

    renderWithProviders(<SecretKeyPicker value="" onChange={mockOnChange} />);

    // Wait for health check to run (health query is run on mount)
    await waitFor(() => {
      // The vault opener button should be hidden because health is down
      expect(screen.queryByTestId("secret-key-picker-vault-btn")).not.toBeInTheDocument();
    });
  });

  /**
   * The vault popup's own keyboard navigation.
   *
   * Opening the popup moves focus into its filter input, so the main input's
   * key handler — which owns `highlightedIndex` — stops receiving anything, and
   * the popup is a sibling of that input rather than a child, so nothing
   * reached it by bubbling either. The popup's own handler called
   * `preventDefault()` on both arrows and left a comment saying the parent
   * would handle them. Nothing did: the list was mouse-only.
   */
  describe("keyboard navigation in the vault popup", () => {
    async function openPopup() {
      const user = userEvent.setup();
      renderWithProviders(<SecretKeyPicker value="" onChange={mockOnChange} />);
      await user.click(await screen.findByTestId("secret-key-picker-vault-btn"));
      await screen.findByTestId("vault-popup");
      // The popup focuses its filter on a timer; the arrows are meaningless
      // until it has, because that is what makes the main input lose focus.
      await waitFor(() =>
        expect(screen.getByTestId("vault-popup-filter")).toHaveFocus(),
      );
      return user;
    }

    it("moves the highlight down with ArrowDown", async () => {
      const user = await openPopup();

      await user.keyboard("{ArrowDown}");

      expect(screen.getByTestId("vault-key-openai-key")).toHaveAttribute(
        "aria-selected",
        "true",
      );
    });

    it("selects the highlighted key with Enter", async () => {
      const user = await openPopup();

      await user.keyboard("{ArrowDown}{ArrowDown}{Enter}");

      // Second key in the sorted list.
      expect(mockOnChange).toHaveBeenCalledWith("${vault:slack-token}");
    });

    it("wraps to the last key with ArrowUp from the top", async () => {
      const user = await openPopup();

      await user.keyboard("{ArrowUp}");

      expect(screen.getByTestId("vault-key-slack-token")).toHaveAttribute(
        "aria-selected",
        "true",
      );
    });

    it("announces the highlighted key rather than only colouring it", async () => {
      // The highlight is a background colour, which is nothing to a screen
      // reader. aria-activedescendant is what makes the arrows perceivable.
      const user = await openPopup();

      await user.keyboard("{ArrowDown}");

      // Asserts the relationship, not a literal id: the ids are generated
      // (`useId`), because nothing validates a vault key name and one with a
      // space in it would produce an id no reference could resolve.
      const active = screen
        .getByTestId("vault-popup-filter")
        .getAttribute("aria-activedescendant");
      expect(active).toBeTruthy();
      expect(document.getElementById(active!)).toBe(
        screen.getByTestId("vault-key-openai-key"),
      );
    });

    it("still resolves the reference when a key name contains a space", async () => {
      // Nothing validates a vault key name — the create dialog only trims it —
      // so a key called "my key" is reachable from this very UI. Building an id
      // out of the name would put a space in it, and an id with a space is one
      // `aria-activedescendant` can never resolve: the arrows would move a
      // highlight that announces nothing.
      server.use(
        http.get("*/secretstore/secrets/default", () =>
          HttpResponse.json([
            {
              tenantId: "default",
              keyName: "my key",
              createdAt: "2026-06-08T12:00:00Z",
              lastAccessedAt: null,
              lastRotatedAt: null,
              checksum: "abc",
              description: "A key whose name has a space in it",
              allowedAgents: ["*"],
            },
          ]),
        ),
      );
      const user = await openPopup();

      await user.keyboard("{ArrowDown}");

      const active = screen
        .getByTestId("vault-popup-filter")
        .getAttribute("aria-activedescendant");
      expect(active).toBeTruthy();
      expect(active).not.toContain(" ");
      expect(document.getElementById(active!)).toBe(
        screen.getByTestId("vault-key-my key"),
      );
    });

    it("lets the Create button activate on Enter after tabbing to it", async () => {
      // The container handler sees Enter bubbling from every focusable thing
      // inside the popup, not just the filter. Unguarded it calls
      // preventDefault(), which kills the focused button's own activation —
      // the same defect the connections review found on the cards.
      const user = await openPopup();

      screen.getByTestId("vault-popup-create").focus();
      await user.keyboard("{Enter}");

      expect(await screen.findByRole("dialog")).toBeInTheDocument();
    });

    it("does not select a key when Enter is pressed on the Create button", async () => {
      // The worse half: with a highlight set, the swallowed Enter selected the
      // highlighted key instead of doing what the focused button says.
      const user = await openPopup();

      await user.keyboard("{ArrowDown}");
      screen.getByTestId("vault-popup-create").focus();
      await user.keyboard("{Enter}");

      expect(mockOnChange).not.toHaveBeenCalled();
    });

    it("activates the option that has focus, not the one highlighted", async () => {
      const user = await openPopup();

      await user.keyboard("{ArrowDown}"); // highlights openai-key
      screen.getByTestId("vault-key-slack-token").focus();
      await user.keyboard("{Enter}");

      expect(mockOnChange).toHaveBeenCalledWith("${vault:slack-token}");
    });

    it("still closes on Escape", async () => {
      // Escape used to be handled by the popup's own handler, which this change
      // replaced wholesale with the parent's.
      const user = await openPopup();

      await user.keyboard("{Escape}");

      await waitFor(() =>
        expect(screen.queryByTestId("vault-popup")).not.toBeInTheDocument(),
      );
    });

    it("closes on Escape from a focused button, not just from the filter", async () => {
      // Why the handler stays on the container rather than moving onto the
      // filter input: Escape means "close this popup" wherever focus is inside
      // it. Only the navigation keys are scoped to the filter.
      const user = await openPopup();

      screen.getByTestId("vault-popup-create").focus();
      await user.keyboard("{Escape}");

      await waitFor(() =>
        expect(screen.queryByTestId("vault-popup")).not.toBeInTheDocument(),
      );
    });

    it("does not select anything when Enter is pressed with no highlight", async () => {
      const user = await openPopup();

      await user.keyboard("{Enter}");

      expect(mockOnChange).not.toHaveBeenCalled();
    });
  });
});
