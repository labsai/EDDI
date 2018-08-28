# Make it more obvious that a PR is a work in progress and shouldn't be merged yet
warn("PR is classed as Work in Progress") if github.pr_title.include? "WIP"

fail("PR does not conform to naming guidelines") if !github.pr_title.match(%r{^(feat|docs|test|fix|refactor|chore|style)(\([a-z0-9\-]+\))?\:(\s){1}(\S)+.*?$})

# Warn when there is a big PR
warn("Big PR") if git.lines_of_code > 500

# Don't let testing shortcuts get into master by accident
fail("fdescribe left in tests") if `grep -r --include \*.spec.ts fdescribe .`.length > 1
fail("describe.only left in tests") if `grep -r --include \*.spec.ts describe.only .`.length > 1
fail("fit left in tests") if `grep -r --include \*.spec.ts fit .`.length > 1
fail("it.only left in tests") if `grep -r --include \*.spec.ts it.only .`.length > 1

if github.pr_body.length < 230
  warn "Please provide a summary in the Pull Request description"
end
