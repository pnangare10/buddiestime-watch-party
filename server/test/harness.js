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
    // Tests that deliberately provoke a server crash find the child already dead.
    // Waiting on "exit" then hangs forever (the event fired long ago), stalling the
    // whole file and reporting every test as "promise still pending" — which looks
    // like a product failure but is purely a harness artefact. Check first.
    stop: () =>
      new Promise((r) => {
        if (child.exitCode !== null || child.signalCode !== null) return r();
        child.once("exit", () => r());
        child.kill("SIGKILL");
      }),
    /** Has the server process died on its own? Crash tests assert this is false. */
    crashed: () => child.exitCode !== null || child.signalCode !== null,
  };
}

module.exports = { startServer };
