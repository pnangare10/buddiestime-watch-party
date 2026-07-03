const { spawn } = require("child_process");
const path = require("path");
const http = require("http");

function waitForHealth(baseUrl, timeoutMs = 8000) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve, reject) => {
    const tick = () => {
      const req = http.get(baseUrl + "/health", (res) => {
        res.resume();
        if (res.statusCode === 200) return resolve();
        retry();
      });
      req.on("error", retry);
    };
    const retry = () =>
      Date.now() > deadline
        ? reject(new Error("health timeout"))
        : setTimeout(tick, 100);
    tick();
  });
}

async function startServer({ port = 8099, env = {} } = {}) {
  const serverPath = path.join(__dirname, "..", "server.js");
  const child = spawn(process.execPath, [serverPath], {
    cwd: path.join(__dirname, ".."), // run from server/ so dotenv + relative paths resolve on Windows
    env: { ...process.env, PORT: String(port), ...env },
    stdio: ["ignore", "ignore", "inherit"],
  });
  const baseUrl = `http://localhost:${port}`;
  await waitForHealth(baseUrl);
  return {
    baseUrl,
    wsUrl: `ws://localhost:${port}`,
    stop: () =>
      new Promise((r) => {
        child.once("exit", () => r());
        child.kill("SIGKILL");
      }),
  };
}

module.exports = { startServer };
