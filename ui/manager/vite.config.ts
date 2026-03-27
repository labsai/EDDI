import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { fileURLToPath, URL } from "node:url";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  server: {
    port: 3000,
    proxy: {
      "/agentstore": "http://localhost:7070",
      "/workflowstore": "http://localhost:7070",
      "/rulestore": "http://localhost:7070",
      "/dictionarystore": "http://localhost:7070",
      "/outputstore": "http://localhost:7070",
      "/apicallstore": "http://localhost:7070",
      "/llmstore": "http://localhost:7070",
      "/propertysetterstore": "http://localhost:7070",
      "/mcpcallsstore": "http://localhost:7070",
      "/ragstore": "http://localhost:7070",
      "/parserstore": "http://localhost:7070",
      "/extensionstore": "http://localhost:7070",
      "/conversationstore": "http://localhost:7070",
      "/descriptorstore": "http://localhost:7070",
      "/secretstore": "http://localhost:7070",
      "/auditstore": "http://localhost:7070",
      "/schedulestore": "http://localhost:7070",
      "/deploymentstore": "http://localhost:7070",
      "/propertiesstore": "http://localhost:7070",
      "/administration": "http://localhost:7070",
      "/agents": "http://localhost:7070",
      "/backup": "http://localhost:7070",
      "/managerresource": "http://localhost:7070",
      "/managedagents": "http://localhost:7070",
      "/mcp": "http://localhost:7070",
    },
  },
});
