'use strict';
// Stand-alone server for local tests: node server/test-server.js 18080
const http = require('http');
const bridge = require('./mesh-bridge')();
const port = parseInt(process.argv[2], 10) || 18080;
http.createServer((req, res) => {
  if (req.url.startsWith('/api/mesh')) { req.url = req.url.slice('/api/mesh'.length) || '/'; return bridge(req, res); }
  res.statusCode = 404; res.end('no');
}).listen(port, '127.0.0.1', () => console.log('listening ' + port));
