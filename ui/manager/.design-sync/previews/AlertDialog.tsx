import { AlertDialog } from "eddi-manager";

export const Destructive = () => (
  <AlertDialog
    open
    onOpenChange={() => {}}
    title="Delete this workflow?"
    description="The “Onboarding” workflow and its 12 steps will be permanently deleted."
    confirmLabel="Delete"
    cancelLabel="Cancel"
    onConfirm={() => {}}
    variant="destructive"
  />
);

export const Warning = () => (
  <AlertDialog
    open
    onOpenChange={() => {}}
    title="Discard changes?"
    description="You have unsaved edits to this agent. Leaving now will discard them."
    confirmLabel="Discard"
    cancelLabel="Keep editing"
    onConfirm={() => {}}
    variant="warning"
  />
);
