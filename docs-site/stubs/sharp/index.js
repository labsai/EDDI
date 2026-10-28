// See package.json: astro.config.mjs selects the pass-through image service, so nothing imports this.
throw new Error('sharp is not installed in docs-site: the site uses the pass-through image service (see astro.config.mjs)');
