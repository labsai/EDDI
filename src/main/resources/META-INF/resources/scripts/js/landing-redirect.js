// Returning users go straight to their preferred workspace;
// first-time visitors go to the welcome chooser.
(function () {
  var pref = null;
  try { pref = localStorage.getItem('eddi-landing-preference'); } catch(e) {}
  if (pref === 'manage' || pref === 'workforce') {
    window.location.replace('/' + pref);
  } else {
    window.location.replace('/welcome');
  }
})();
