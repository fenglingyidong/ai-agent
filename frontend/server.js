const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");

const rootDir = __dirname;
const port = Number.parseInt(process.argv[2] || "4173", 10);

const contentTypes = {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".svg": "image/svg+xml",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".ico": "image/x-icon"
};

const server = http.createServer((request, response) => {
    const urlPath = decodeURIComponent((request.url || "/").split("?")[0]);
    const safePath = path.normalize(urlPath).replace(/^(\.\.[/\\])+/, "");
    let filePath = path.join(rootDir, safePath === path.sep ? "index.html" : safePath);

    if (!filePath.startsWith(rootDir)) {
        sendText(response, 403, "Forbidden");
        return;
    }

    fs.stat(filePath, (statError, stats) => {
        if (!statError && stats.isDirectory()) {
            filePath = path.join(filePath, "index.html");
        }

        fs.readFile(filePath, (readError, content) => {
            if (readError) {
                sendText(response, 404, "Not Found");
                return;
            }

            const extension = path.extname(filePath).toLowerCase();
            response.writeHead(200, {
                "Content-Type": contentTypes[extension] || "application/octet-stream",
                "Cache-Control": "no-store"
            });
            response.end(content);
        });
    });
});

server.listen(port, () => {
    console.log(`Frontend server running at http://localhost:${port}`);
});

function sendText(response, statusCode, message) {
    response.writeHead(statusCode, { "Content-Type": "text/plain; charset=utf-8" });
    response.end(message);
}
