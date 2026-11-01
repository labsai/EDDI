// @ts-check
import { readFileSync } from 'node:fs';
import starlight from '@astrojs/starlight';
import { defineConfig, passthroughImageService } from 'astro/config';
import mermaid from 'astro-mermaid';
import starlightLinksValidator from 'starlight-links-validator';
import starlightLlmsTxt from 'starlight-llms-txt';

// Written by scripts/sync-docs.mjs from docs/SUMMARY.md (npm run sync, which
// dev and build run first). Everything generated lives under .generated/.
const sidebarFile = new URL('./.generated/sidebar.json', import.meta.url);
let sidebar;
try {
	sidebar = JSON.parse(readFileSync(sidebarFile, 'utf8'));
} catch {
	throw new Error('.generated/sidebar.json is missing - run `npm run sync` first.');
}

const site = 'https://docs.eddi.technology';

/**
 * Every page as a Markdown list linking its raw .md copy, grouped like the
 * sidebar — what GitBook's /llms.txt listed, so an agent can fetch one page
 * instead of the multi-megabyte llms-full.txt.
 */
function pageIndex(items, depth = 0) {
	return items
		.flatMap((item) => {
			const indent = '  '.repeat(depth);
			if (item.link) {
				const md = item.link === '/' ? '/index.md' : `${item.link.replace(/\/$/, '')}.md`;
				return [`${indent}- [${item.label}](${site}${md})`];
			}
			return [`${indent}- ${item.label}`, pageIndex(item.items, depth + 1)];
		})
		.join('\n');
}

export default defineConfig({
	site,
	publicDir: './.generated/public',
	outDir: './.generated/dist',
	// No image processing: the docs have no raster images (the logos are SVG, badges are remote).
	// Astro's default service needs sharp, whose libvips binaries are LGPL-3.0, which the
	// repository's dependency licence policy (dependency-review.yml) denies — so package.json
	// overrides sharp with the stub in stubs/sharp and nothing here ever loads it.
	image: { service: passthroughImageService() },
	// The backend and the Manager link to /secrets-vault, /clustering, ... without a slash.
	trailingSlash: 'ignore',
	integrations: [
		// Before Starlight, so its remark plugin sees ```mermaid blocks before Expressive Code does.
		mermaid({ autoTheme: true }),
		starlight({
			title: 'EDDI Documentation',
			description:
				'Documentation for EDDI, the config-driven multi-agent orchestration middleware for conversational AI.',
			logo: {
				dark: './src/assets/eddi-logo-dark.svg',
				light: './src/assets/eddi-logo-light.svg',
				replacesTitle: true,
			},
			// The website's favicons (eddi.technology/public), so a docs tab looks like a site tab.
			favicon: '/favicon.svg',
			head: [{ tag: 'link', attrs: { rel: 'icon', type: 'image/x-icon', href: '/favicon.ico', sizes: '48x48' } }],
			// The right side of the website's header, and its sun/moon toggle instead of a <select>.
			components: {
				SocialIcons: './src/components/HeaderActions.astro',
				ThemeSelect: './src/components/ThemeToggle.astro',
			},
			// Fonts and colours: the website's global.css; see the header of eddi.css.
			customCss: [
				'@fontsource-variable/outfit',
				'@fontsource/jetbrains-mono/400.css',
				'@fontsource/jetbrains-mono/500.css',
				'./src/styles/eddi.css',
			],
			// Code blocks as the website renders them: Astro's default github-dark theme in both
			// colour schemes, 0.5rem radius, the site's border, 1rem/1.25rem padding, 0.875rem/1.6.
			expressiveCode: {
				themes: ['github-dark'],
				useStarlightUiThemeColors: false,
				defaultProps: { frame: 'none' },
				styleOverrides: {
					// Expressive Code adds the 1px border to the radius; the website's <pre> is 8px overall.
					borderRadius: 'calc(0.5rem - 1px)',
					borderColor: 'var(--eddi-border)',
					codeFontFamily: 'var(--eddi-font-mono)',
					codeFontSize: '0.875rem',
					codeLineHeight: '1.6',
					codePaddingBlock: '1rem',
					codePaddingInline: '1.25rem',
					uiFontFamily: 'var(--eddi-font-sans)',
				},
			},
			sidebar,
			plugins: [
				starlightLinksValidator({
					// Checked by scripts/sync-docs.mjs instead: under Astro 7's satteri processor this plugin
					// runs before heading ids exist, so it would report every #anchor as broken.
					errorOnInvalidHashes: false,
					// The docs link to a locally running EDDI (http://localhost:7070, /q/dev, ...) on purpose.
					errorOnLocalLinks: false,
					// The home page is keyed by its path under src/content/docs/, which the generated
					// content does not live in; a link to / cannot be broken anyway.
					exclude: ['/'],
				}),
				starlightLlmsTxt({
					projectName: 'EDDI',
					details: `Every page is also available as Markdown at its URL plus \`.md\`:\n\n${pageIndex(sidebar)}`,
					rawContent: true,
					// llms-small.txt is the "just the reference" variant; the engineering log is most of the bytes.
					exclude: ['changelog', 'changelog/**'],
				}),
			],
		}),
	],
});
