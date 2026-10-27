import { defineCollection } from 'astro:content';
import { glob } from 'astro/loaders';
import { docsSchema } from '@astrojs/starlight/schema';

// Starlight's docsLoader() is this glob with its base fixed at src/content/docs/.
// The pages are generated from docs/ by scripts/sync-docs.mjs into .generated/,
// so the loader points there instead — edit docs/, never the generated copy.
export const collections = {
	docs: defineCollection({
		loader: glob({ base: './.generated/content', pattern: '**/[^_]*.md' }),
		schema: docsSchema(),
	}),
};
