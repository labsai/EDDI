import { EmptyState } from "eddi-manager";
import { Bot, Inbox } from "lucide-react";

export const NoAgents = () => (
  <div style={{ padding: 16, maxWidth: 480 }}>
    <EmptyState
      icon={Bot}
      title="No agents yet"
      description="Create your first agent to start automating conversations."
      actionLabel="New agent"
      onAction={() => {}}
    />
  </div>
);

export const NoResults = () => (
  <div style={{ padding: 16, maxWidth: 480 }}>
    <EmptyState
      icon={Inbox}
      title="No conversations found"
      description="Try adjusting your filters or search terms."
    />
  </div>
);
