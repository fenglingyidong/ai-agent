import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
    plugins: [vue()],
    server: {
        host: "0.0.0.0",
        port: 4173,
        strictPort: true
    },
    preview: {
        host: "0.0.0.0",
        port: 4173,
        strictPort: true
    },
    test: {
        environment: "node"
    }
});
