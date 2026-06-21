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
  ],
  rules: {
    quotes: ["error", "double"],
    "import/no-unresolved": "off",
    indent: ["error", 2],
    "object-curly-spacing": ["error", "always"],
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
