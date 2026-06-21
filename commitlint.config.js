// Added by wf-meta build-pipeline — plan v1, 2026-06-20
//
// The repo vendors a third-party subtree (summarizer/summarize-daemon) carrying its
// own non-conventional history, and predates Conventional Commits for its earliest
// scaffolding commits. Those are not rewritten, so they are ignored here; the gate
// still enforces Conventional Commits on all first-party commits going forward.
const LEGACY_PATTERNS = [
  /^Squashed '.+?'/, // git subtree --squash pulls
  /^Merge commit '.+?'/, // git subtree merge commits
  /^Add '.+?' from commit/, // git subtree add commits
  /^Initial commit/, // scaffolding / subtree roots
  /^(Add|Wire|Scaffold) /, // pre-convention vendored daemon history
];

module.exports = {
  extends: ["@commitlint/config-conventional"],
  ignores: [(message) => LEGACY_PATTERNS.some((re) => re.test(message))],
  rules: {
    // Subject casing varies across vendored/legacy commits; don't block on style.
    "subject-case": [0],
  },
};
