// Terminates sockets that failed to pong since the last sweep; pings the rest
// and marks them pending (isAlive=false) until their pong handler flips it back.
function sweepClients(clients) {
  let pinged = 0;
  let terminated = 0;
  clients.forEach((ws) => {
    if (ws.isAlive === false) {
      console.log("[WS] terminating dead socket (no pong)");
      ws.terminate();
      terminated++;
      return;
    }
    ws.isAlive = false;
    try {
      ws.ping();
      pinged++;
    } catch (e) {
      /* socket already gone */
    }
  });
  return { pinged, terminated };
}

module.exports = { sweepClients };
