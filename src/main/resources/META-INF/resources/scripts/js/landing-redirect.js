// Returning Workforce users go straight there; everyone else goes to the
// welcome chooser, which is part of the Manager app and knows the signed-in
// user's roles: it sends an eddi-user straight on to /workforce, and an editor
// or admin on to a remembered "manage" preference. This script runs before
// sign-in and cannot read roles, so it must not send anyone to /manage itself.
(function () {
  var pref = null;
  // Reading localStorage throws in private mode, with site data blocked, or
  // inside a sandboxed iframe. There is nothing to recover — the visitor simply
  // has no stored preference — so fall through to the welcome chooser below.
  try { pref = localStorage.getItem('eddi-landing-preference'); } catch (e) { /* no stored preference available */ }
  if (pref === 'workforce') {
    window.location.replace('/workforce');
  } else {
    window.location.replace('/welcome');
  }
})();
