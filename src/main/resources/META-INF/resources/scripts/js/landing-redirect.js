// Returning users go straight to their preferred workspace;
// first-time visitors go to the welcome chooser.
(function () {
  var pref = null;
  // Reading localStorage throws in private mode, with site data blocked, or
  // inside a sandboxed iframe. There is nothing to recover — the visitor simply
  // has no stored preference — so fall through to the welcome chooser below.
  try { pref = localStorage.getItem('eddi-landing-preference'); } catch (e) { /* no stored preference available */ }
  if (pref === 'manage' || pref === 'workforce') {
    window.location.replace('/' + pref);
  } else {
    window.location.replace('/welcome');
  }
})();
