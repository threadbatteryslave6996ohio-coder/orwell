/** @type {import("next").NextConfig} */
const config = {
  reactStrictMode: true,
  // better-sqlite3 is a native module; keep webpack from trying to bundle it.
  serverExternalPackages: ["better-sqlite3"],
};

export default config;
