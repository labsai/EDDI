// Everyone goes to the welcome chooser, which is part of the Manager app and
// knows the signed-in user's roles: it sends an eddi-user straight on to
// /workforce, an editor or admin on to a remembered "workforce" or "manage"
// preference, and a dedicated viewer or approver to the Manager. This script
// runs before sign-in and cannot read roles, so it must not send anyone to
// /workforce or /manage itself: a remembered Workforce preference would let a
// viewer or approver reach the end-user catalog, whose endpoints refuse them.
// (/welcome loads the same bundle as /workforce, so going through it is free.)
(function () {
  window.location.replace('/welcome');
})();
