import { describe, it, expect } from "vitest";
import { screen, within } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { TaskBoard } from "../task-board";

/* ------------------------------------------------------------------ */
/*  Helpers                                                            */
/* ------------------------------------------------------------------ */

function makeTask(
  id: string,
  subject: string,
  assignedTo: string,
  priority: number,
) {
  return { id, subject, assignedTo, priority };
}

/** Minimal default props – override per test */
function defaultProps() {
  return {
    taskPlan: null as ReturnType<typeof makeTask>[] | null,
    tasksInProgress: new Set<string>(),
    tasksCompleted: new Set<string>(),
    taskVerifications: new Map<string, { passed: boolean; feedback: string }>(),
    isStreaming: false,
  };
}

/* ------------------------------------------------------------------ */
/*  Tests                                                              */
/* ------------------------------------------------------------------ */

describe("TaskBoard", () => {
  // ------------------------------------------------------------------
  //  1. Empty state
  // ------------------------------------------------------------------
  it("renders empty state when taskPlan is null", () => {
    renderWithProviders(<TaskBoard {...defaultProps()} />);

    expect(screen.getByTestId("task-board-empty")).toBeInTheDocument();
    expect(
      screen.getByText(
        "Task plan will appear here when the moderator creates it",
      ),
    ).toBeInTheDocument();
    // The main board should NOT be rendered
    expect(screen.queryByTestId("task-board")).not.toBeInTheDocument();
  });

  // ------------------------------------------------------------------
  //  2. Progress bar with correct counts
  // ------------------------------------------------------------------
  it("renders progress bar with correct done/total counts", () => {
    const tasks = [
      makeTask("t1", "Task 1", "Agent A", 0),
      makeTask("t2", "Task 2", "Agent B", 1),
      makeTask("t3", "Task 3", "Agent C", 2),
      makeTask("t4", "Task 4", "Agent D", 3),
    ];

    renderWithProviders(
      <TaskBoard
        {...defaultProps()}
        taskPlan={tasks}
        tasksCompleted={new Set(["t1"])}
        taskVerifications={
          new Map([["t2", { passed: true, feedback: "Looks good" }]])
        }
      />,
    );

    // done = completed(1) + verified(1) = 2, total = 4 → 50%
    expect(screen.getByText("2/4 (50%)")).toBeInTheDocument();
  });

  // ------------------------------------------------------------------
  //  3. Task cards in correct columns
  // ------------------------------------------------------------------
  it("distributes tasks into correct columns based on status", () => {
    const tasks = [
      makeTask("pending-1", "Pending task", "Alice", 2),
      makeTask("active-1", "Active task", "Bob", 1),
      makeTask("done-1", "Done task", "Carol", 0),
      makeTask("verified-1", "Verified task", "Dave", 3),
    ];

    renderWithProviders(
      <TaskBoard
        {...defaultProps()}
        taskPlan={tasks}
        tasksInProgress={new Set(["active-1"])}
        tasksCompleted={new Set(["done-1"])}
        taskVerifications={
          new Map([["verified-1", { passed: true, feedback: "OK" }]])
        }
      />,
    );

    // Pending column has pending-1
    const pendingCol = screen.getByTestId("task-column-pending");
    expect(
      within(pendingCol).getByTestId("task-card-pending-1"),
    ).toBeInTheDocument();

    // Active column has active-1
    const activeCol = screen.getByTestId("task-column-in-progress");
    expect(
      within(activeCol).getByTestId("task-card-active-1"),
    ).toBeInTheDocument();

    // Done column has done-1
    const doneCol = screen.getByTestId("task-column-completed");
    expect(
      within(doneCol).getByTestId("task-card-done-1"),
    ).toBeInTheDocument();

    // Verified column has verified-1
    const verifiedCol = screen.getByTestId("task-column-verified");
    expect(
      within(verifiedCol).getByTestId("task-card-verified-1"),
    ).toBeInTheDocument();
  });

  // ------------------------------------------------------------------
  //  4. Streaming indicator visible when isStreaming=true
  // ------------------------------------------------------------------
  it("shows streaming indicator when isStreaming is true", () => {
    renderWithProviders(
      <TaskBoard
        {...defaultProps()}
        taskPlan={[makeTask("t1", "Task", "Agent", 0)]}
        isStreaming={true}
      />,
    );

    const progressBar = screen.getByTestId("task-board-progress");
    // Loader2 renders as SVG with animate-spin class
    const spinner = progressBar.querySelector(".animate-spin");
    expect(spinner).toBeInTheDocument();
  });

  // ------------------------------------------------------------------
  //  5. Streaming indicator hidden when isStreaming=false
  // ------------------------------------------------------------------
  it("hides streaming indicator when isStreaming is false", () => {
    renderWithProviders(
      <TaskBoard
        {...defaultProps()}
        taskPlan={[makeTask("t1", "Task", "Agent", 0)]}
        isStreaming={false}
      />,
    );

    const progressBar = screen.getByTestId("task-board-progress");
    const spinner = progressBar.querySelector(".animate-spin");
    expect(spinner).not.toBeInTheDocument();
  });

  // ------------------------------------------------------------------
  //  6. Verified-passed card with green feedback
  // ------------------------------------------------------------------
  it("renders verified-passed card with feedback text", () => {
    const tasks = [makeTask("v1", "Verify me", "Agent V", 1)];
    const verifications = new Map([
      ["v1", { passed: true, feedback: "All checks passed" }],
    ]);

    renderWithProviders(
      <TaskBoard
        {...defaultProps()}
        taskPlan={tasks}
        taskVerifications={verifications}
      />,
    );

    // Scope within the desktop verified column to avoid mobile duplicate
    const verifiedCol = screen.getByTestId("task-column-verified");
    const card = within(verifiedCol).getByTestId("task-card-v1");
    expect(within(card).getByText("All checks passed")).toBeInTheDocument();
    // The feedback container should have emerald (green) styling
    const feedbackEl = within(card).getByText("All checks passed").parentElement!;
    expect(feedbackEl.className).toMatch(/emerald/);
  });

  // ------------------------------------------------------------------
  //  7. Verified-failed card with red feedback
  // ------------------------------------------------------------------
  it("renders verified-failed card with feedback text", () => {
    const tasks = [makeTask("f1", "Failing task", "Agent F", 0)];
    const verifications = new Map([
      ["f1", { passed: false, feedback: "Quality check failed" }],
    ]);

    renderWithProviders(
      <TaskBoard
        {...defaultProps()}
        taskPlan={tasks}
        taskVerifications={verifications}
      />,
    );

    // Scope within the desktop verified column to avoid mobile duplicate
    const verifiedCol = screen.getByTestId("task-column-verified");
    const card = within(verifiedCol).getByTestId("task-card-f1");
    expect(
      within(card).getByText("Quality check failed"),
    ).toBeInTheDocument();
    // The feedback container should have destructive (red) styling
    const feedbackEl =
      within(card).getByText("Quality check failed").parentElement!;
    expect(feedbackEl.className).toMatch(/destructive/);
  });

  // ------------------------------------------------------------------
  //  8. Priority badges (P0, P1, P2, P3)
  // ------------------------------------------------------------------
  it("shows correct priority badges for P0, P1, P2, P3", () => {
    const tasks = [
      makeTask("p0", "Critical", "A", 0),
      makeTask("p1", "High", "B", 1),
      makeTask("p2", "Medium", "C", 2),
      makeTask("p3", "Low", "D", 3),
    ];

    renderWithProviders(
      <TaskBoard {...defaultProps()} taskPlan={tasks} />,
    );

    // All tasks are pending — scope within desktop pending column
    const pendingCol = screen.getByTestId("task-column-pending");

    const p0Card = within(pendingCol).getByTestId("task-card-p0");
    expect(within(p0Card).getByText("P0")).toBeInTheDocument();

    const p1Card = within(pendingCol).getByTestId("task-card-p1");
    expect(within(p1Card).getByText("P1")).toBeInTheDocument();

    const p2Card = within(pendingCol).getByTestId("task-card-p2");
    expect(within(p2Card).getByText("P2")).toBeInTheDocument();

    const p3Card = within(pendingCol).getByTestId("task-card-p3");
    expect(within(p3Card).getByText("P3")).toBeInTheDocument();
  });

  // ------------------------------------------------------------------
  //  9. Unknown priority falls back to P3
  // ------------------------------------------------------------------
  it("handles unknown priority gracefully by falling back to P3", () => {
    const tasks = [makeTask("px", "Unknown priority", "Agent X", 5)];

    renderWithProviders(
      <TaskBoard {...defaultProps()} taskPlan={tasks} />,
    );

    // Scope within desktop pending column to avoid mobile duplicate
    const pendingCol = screen.getByTestId("task-column-pending");
    const card = within(pendingCol).getByTestId("task-card-px");
    // Fallback: PRIORITY_CONFIG[5] is undefined → falls back to PRIORITY_CONFIG[3] which has label "P3"
    expect(within(card).getByText("P3")).toBeInTheDocument();
  });

  // ------------------------------------------------------------------
  //  10. Agent avatar with initials and name
  // ------------------------------------------------------------------
  it("shows agent avatar with initials and agent name", () => {
    const tasks = [makeTask("a1", "Agent task", "Research Agent", 2)];

    renderWithProviders(
      <TaskBoard {...defaultProps()} taskPlan={tasks} />,
    );

    // Scope within desktop pending column to avoid mobile duplicate
    const pendingCol = screen.getByTestId("task-column-pending");
    const card = within(pendingCol).getByTestId("task-card-a1");
    // Agent name text should appear
    expect(within(card).getByText("Research Agent")).toBeInTheDocument();
    // Avatar should show initials (getInitials("Research Agent") → "RA")
    expect(within(card).getByText("RA")).toBeInTheDocument();
  });

  // ------------------------------------------------------------------
  //  11. Empty columns show placeholder text
  // ------------------------------------------------------------------
  it("shows dash placeholder in empty columns", () => {
    // Single pending task → Active, Done, Verified are empty
    const tasks = [makeTask("only", "Only task", "Solo", 1)];

    renderWithProviders(
      <TaskBoard {...defaultProps()} taskPlan={tasks} />,
    );

    // The "in-progress", "completed", "verified" columns should have "—" placeholder
    const activeCol = screen.getByTestId("task-column-in-progress");
    expect(within(activeCol).getByText("—")).toBeInTheDocument();

    const doneCol = screen.getByTestId("task-column-completed");
    expect(within(doneCol).getByText("—")).toBeInTheDocument();

    const verifiedCol = screen.getByTestId("task-column-verified");
    expect(within(verifiedCol).getByText("—")).toBeInTheDocument();
  });

  // ------------------------------------------------------------------
  //  12. Section heading with Task Board title
  // ------------------------------------------------------------------
  it("shows section heading with Task Board title", () => {
    renderWithProviders(
      <TaskBoard
        {...defaultProps()}
        taskPlan={[makeTask("t1", "Task", "Agent", 0)]}
      />,
    );

    expect(screen.getByText("Task Board")).toBeInTheDocument();
  });

  // ------------------------------------------------------------------
  //  13. Progress bar percentage
  // ------------------------------------------------------------------
  it("renders progress bar with correct percentage via aria-valuenow", () => {
    const tasks = [
      makeTask("t1", "T1", "A", 0),
      makeTask("t2", "T2", "B", 0),
      makeTask("t3", "T3", "C", 0),
      makeTask("t4", "T4", "D", 0),
    ];

    renderWithProviders(
      <TaskBoard
        {...defaultProps()}
        taskPlan={tasks}
        tasksCompleted={new Set(["t1", "t2", "t3"])}
      />,
    );

    // 3 done out of 4 → 75%
    const progressBar = screen.getByRole("progressbar");
    expect(progressBar).toHaveAttribute("aria-valuenow", "75");
    expect(screen.getByText("3/4 (75%)")).toBeInTheDocument();
  });

  // ------------------------------------------------------------------
  //  14. All 4 columns are always rendered (desktop)
  // ------------------------------------------------------------------
  it("renders all four columns in the desktop layout", () => {
    const tasks = [makeTask("t1", "One task", "Agent", 0)];

    renderWithProviders(
      <TaskBoard {...defaultProps()} taskPlan={tasks} />,
    );

    expect(screen.getByTestId("task-column-pending")).toBeInTheDocument();
    expect(
      screen.getByTestId("task-column-in-progress"),
    ).toBeInTheDocument();
    expect(screen.getByTestId("task-column-completed")).toBeInTheDocument();
    expect(screen.getByTestId("task-column-verified")).toBeInTheDocument();
  });

  // ------------------------------------------------------------------
  //  15. Multiple tasks distributed across columns
  // ------------------------------------------------------------------
  it("distributes multiple tasks across all four columns correctly", () => {
    const tasks = [
      makeTask("p1", "Pending 1", "A", 0),
      makeTask("p2", "Pending 2", "B", 1),
      makeTask("a1", "Active 1", "C", 2),
      makeTask("a2", "Active 2", "D", 0),
      makeTask("d1", "Done 1", "E", 1),
      makeTask("v1", "Verified 1", "F", 0),
      makeTask("v2", "Verified 2", "G", 3),
    ];

    renderWithProviders(
      <TaskBoard
        {...defaultProps()}
        taskPlan={tasks}
        tasksInProgress={new Set(["a1", "a2"])}
        tasksCompleted={new Set(["d1"])}
        taskVerifications={
          new Map([
            ["v1", { passed: true, feedback: "Good" }],
            ["v2", { passed: false, feedback: "Needs work" }],
          ])
        }
      />,
    );

    // Pending: p1, p2
    const pendingCol = screen.getByTestId("task-column-pending");
    expect(within(pendingCol).getByTestId("task-card-p1")).toBeInTheDocument();
    expect(within(pendingCol).getByTestId("task-card-p2")).toBeInTheDocument();

    // Active: a1, a2
    const activeCol = screen.getByTestId("task-column-in-progress");
    expect(within(activeCol).getByTestId("task-card-a1")).toBeInTheDocument();
    expect(within(activeCol).getByTestId("task-card-a2")).toBeInTheDocument();

    // Done: d1
    const doneCol = screen.getByTestId("task-column-completed");
    expect(within(doneCol).getByTestId("task-card-d1")).toBeInTheDocument();

    // Verified: v1, v2
    const verifiedCol = screen.getByTestId("task-column-verified");
    expect(
      within(verifiedCol).getByTestId("task-card-v1"),
    ).toBeInTheDocument();
    expect(
      within(verifiedCol).getByTestId("task-card-v2"),
    ).toBeInTheDocument();
  });

  // ------------------------------------------------------------------
  //  16. Column count badges show correct numbers
  // ------------------------------------------------------------------
  it("shows correct task count badges per column header", () => {
    const tasks = [
      makeTask("p1", "Pending 1", "A", 0),
      makeTask("p2", "Pending 2", "B", 1),
      makeTask("a1", "Active 1", "C", 2),
      makeTask("d1", "Done 1", "D", 0),
      makeTask("d2", "Done 2", "E", 1),
      makeTask("d3", "Done 3", "F", 2),
    ];

    renderWithProviders(
      <TaskBoard
        {...defaultProps()}
        taskPlan={tasks}
        tasksInProgress={new Set(["a1"])}
        tasksCompleted={new Set(["d1", "d2", "d3"])}
      />,
    );

    // Pending column header badge should show "2"
    const pendingCol = screen.getByTestId("task-column-pending");
    expect(within(pendingCol).getByText("2")).toBeInTheDocument();

    // Active column header badge should show "1"
    const activeCol = screen.getByTestId("task-column-in-progress");
    expect(within(activeCol).getByText("1")).toBeInTheDocument();

    // Done column header badge should show "3"
    const doneCol = screen.getByTestId("task-column-completed");
    expect(within(doneCol).getByText("3")).toBeInTheDocument();

    // Verified column header badge should show "0"
    const verifiedCol = screen.getByTestId("task-column-verified");
    expect(within(verifiedCol).getByText("0")).toBeInTheDocument();
  });

  // ------------------------------------------------------------------
  //  17. Verification priority: verified > completed > in-progress
  // ------------------------------------------------------------------
  it("prioritises verified status over completed and in-progress", () => {
    // Task is in all three sets — verified should win
    const tasks = [makeTask("multi", "Multi-state", "Agent M", 0)];

    renderWithProviders(
      <TaskBoard
        {...defaultProps()}
        taskPlan={tasks}
        tasksInProgress={new Set(["multi"])}
        tasksCompleted={new Set(["multi"])}
        taskVerifications={
          new Map([["multi", { passed: true, feedback: "Final" }]])
        }
      />,
    );

    // Should appear in verified column, not completed or in-progress
    const verifiedCol = screen.getByTestId("task-column-verified");
    expect(
      within(verifiedCol).getByTestId("task-card-multi"),
    ).toBeInTheDocument();

    const activeCol = screen.getByTestId("task-column-in-progress");
    expect(
      within(activeCol).queryByTestId("task-card-multi"),
    ).not.toBeInTheDocument();

    const doneCol = screen.getByTestId("task-column-completed");
    expect(
      within(doneCol).queryByTestId("task-card-multi"),
    ).not.toBeInTheDocument();
  });

  // ------------------------------------------------------------------
  //  18. Completed status takes priority over in-progress
  // ------------------------------------------------------------------
  it("prioritises completed status over in-progress", () => {
    const tasks = [makeTask("dual", "Dual state", "Agent D", 1)];

    renderWithProviders(
      <TaskBoard
        {...defaultProps()}
        taskPlan={tasks}
        tasksInProgress={new Set(["dual"])}
        tasksCompleted={new Set(["dual"])}
      />,
    );

    const doneCol = screen.getByTestId("task-column-completed");
    expect(
      within(doneCol).getByTestId("task-card-dual"),
    ).toBeInTheDocument();

    const activeCol = screen.getByTestId("task-column-in-progress");
    expect(
      within(activeCol).queryByTestId("task-card-dual"),
    ).not.toBeInTheDocument();
  });

  // ------------------------------------------------------------------
  //  19. Progress bar at 0% with no completed tasks
  // ------------------------------------------------------------------
  it("shows 0% progress when no tasks are completed", () => {
    const tasks = [
      makeTask("t1", "T1", "A", 0),
      makeTask("t2", "T2", "B", 1),
    ];

    renderWithProviders(
      <TaskBoard {...defaultProps()} taskPlan={tasks} />,
    );

    expect(screen.getByText("0/2 (0%)")).toBeInTheDocument();
    const progressBar = screen.getByRole("progressbar");
    expect(progressBar).toHaveAttribute("aria-valuenow", "0");
  });

  // ------------------------------------------------------------------
  //  20. Progress bar at 100% when all tasks are verified
  // ------------------------------------------------------------------
  it("shows 100% progress when all tasks are done or verified", () => {
    const tasks = [
      makeTask("t1", "T1", "A", 0),
      makeTask("t2", "T2", "B", 1),
    ];

    renderWithProviders(
      <TaskBoard
        {...defaultProps()}
        taskPlan={tasks}
        tasksCompleted={new Set(["t1"])}
        taskVerifications={
          new Map([["t2", { passed: true, feedback: "OK" }]])
        }
      />,
    );

    expect(screen.getByText("2/2 (100%)")).toBeInTheDocument();
    const progressBar = screen.getByRole("progressbar");
    expect(progressBar).toHaveAttribute("aria-valuenow", "100");
  });

  // ------------------------------------------------------------------
  //  21. Task card shows subject text
  // ------------------------------------------------------------------
  it("renders the task subject text on the card", () => {
    const tasks = [
      makeTask("s1", "Implement search feature", "Engineer Bot", 0),
    ];

    renderWithProviders(
      <TaskBoard {...defaultProps()} taskPlan={tasks} />,
    );

    // Scope within desktop pending column to avoid mobile duplicate
    const pendingCol = screen.getByTestId("task-column-pending");
    const card = within(pendingCol).getByTestId("task-card-s1");
    expect(
      within(card).getByText("Implement search feature"),
    ).toBeInTheDocument();
  });

  // ------------------------------------------------------------------
  //  22. Verified card with empty feedback falls back to "Verified"
  // ------------------------------------------------------------------
  it("shows fallback text when verified feedback is empty", () => {
    const tasks = [makeTask("ef1", "Empty feedback", "Agent E", 2)];
    const verifications = new Map([
      ["ef1", { passed: true, feedback: "" }],
    ]);

    renderWithProviders(
      <TaskBoard
        {...defaultProps()}
        taskPlan={tasks}
        taskVerifications={verifications}
      />,
    );

    // Scope within desktop verified column to avoid mobile duplicate
    const verifiedCol = screen.getByTestId("task-column-verified");
    const card = within(verifiedCol).getByTestId("task-card-ef1");
    // Empty string is falsy → falls back to t("taskBoard.verified", "Verified")
    expect(within(card).getByText("Verified")).toBeInTheDocument();
  });
});
