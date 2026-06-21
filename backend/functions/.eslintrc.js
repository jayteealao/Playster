module.exports = {
  root: true,
  env: {
    es2022: true,
    node: true,
  },
  parser: "@typescript-eslint/parser",
  parserOptions: {
    project: ["tsconfig.json"],
    sourceType: "module",
    ecmaVersion: 2022,
  },
  plugins: ["@typescript-eslint", "import"],
  extends: [
    "eslint:recommended",
    "plugin:@typescript-eslint/recommended",
    "google",
    // Must be last: turns off all stylistic rules that conflict with Prettier.
    "prettier",
  ],
  rules: {
    // Formatting (quotes, indent, spacing) is owned by Prettier — see "prettier" in extends.
    "import/no-unresolved": "off",
    "max-len": ["warn", { code: 120, ignoreUrls: true, ignoreStrings: true }],
    "require-jsdoc": "off",
    "valid-jsdoc": "off",
    "linebreak-style": "off",
    camelcase: "off",
    "new-cap": ["error", { capIsNew: false }],
    "@typescript-eslint/no-explicit-any": "warn",
    "@typescript-eslint/no-unused-vars": [
      "error",
      { argsIgnorePattern: "^_", varsIgnorePattern: "^_" },
    ],
  },
};
