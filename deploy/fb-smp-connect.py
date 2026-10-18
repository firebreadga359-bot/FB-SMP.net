#!/usr/bin/env python3
"""FB-SMP all-in-one player relay: Java (TCP) + Bedrock (UDP) over WebSocket.

Run this on your machine, then:
  * Minecraft Java:    connect to  127.0.0.1:25565
  * Minecraft Bedrock: add server  127.0.0.1 : 19132

    pip install websockets
    python fb-smp-connect.py
"""
import asyncio
import os
import socket
import struct
import sys

import websockets

WS_TCP_URL = os.environ.get(
    "WS_TCP_URL", "wss://3000-826b241b-32c1-4cf5-a93f-38287f184ad6.daytonaproxy01.eu/"
)
WS_UDP_URL = os.environ.get(
    "WS_UDP_URL", "wss://3001-826b241b-32c1-4cf5-a93f-38287f184ad6.daytonaproxy01.eu/"
)

JAVA_HOST, JAVA_PORT = "127.0.0.1", 25565
BEDROCK_HOST, BEDROCK_PORT = "127.0.0.1", 19132


# ---------- Java (TCP over WS) ----------
async def handle_java(reader, writer):
    try:
        async with websockets.connect(WS_TCP_URL, max_size=None, ping_interval=None) as ws:

            async def tcp_to_ws():
                while True:
                    data = await reader.read(65536)
                    if not data:
                        break
                    await ws.send(data)

            async def ws_to_tcp():
                async for msg in ws:
                    if isinstance(msg, (bytes, bytearray)):
                        writer.write(msg)
                        await writer.drain()

            await asyncio.gather(tcp_to_ws(), ws_to_tcp())
    except Exception as e:
        print("java relay error:", e, file=sys.stderr)
    finally:
        try:
            writer.close()
        except Exception:
            pass


async def serve_java():
    server = await asyncio.start_server(handle_java, JAVA_HOST, JAVA_PORT)
    async with server:
        await server.serve_forever()


# ---------- Bedrock (UDP over WS) ----------
async def bedrock_session(addr, queue, sock, loop):
    try:
        async with websockets.connect(WS_UDP_URL, max_size=None, ping_interval=None) as ws:

            async def udp_to_ws():
                while True:
                    data = await queue.get()
                    if data is None:
                        return
                    await ws.send(struct.pack(">H", len(data)) + data)

            async def ws_to_udp():
                async for msg in ws:
                    if isinstance(msg, (bytes, bytearray)) and len(msg) >= 2:
                        n = struct.unpack(">H", msg[:2])[0]
                        await loop.sock_sendto(sock, bytes(msg[2:2 + n]), addr)

            await asyncio.gather(udp_to_ws(), ws_to_udp())
    except Exception as e:
        print("bedrock relay error:", addr, e, file=sys.stderr)


async def serve_bedrock():
    loop = asyncio.get_event_loop()
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((BEDROCK_HOST, BEDROCK_PORT))
    sock.setblocking(False)
    sessions = {}
    while True:
        data, addr = await loop.sock_recvfrom(sock, 65535)
        sess = sessions.get(addr)
        if sess is None:
            queue = asyncio.Queue()
            sess = (queue, asyncio.create_task(bedrock_session(addr, queue, sock, loop)))
            sessions[addr] = sess
        sess[0].put_nowait(data)


async def main():
    print("FB-SMP relay running:")
    print(f"  Java    -> {JAVA_HOST}:{JAVA_PORT}")
    print(f"  Bedrock -> {BEDROCK_HOST}:{BEDROCK_PORT}")
    await asyncio.gather(serve_java(), serve_bedrock())


if __name__ == "__main__":
    asyncio.run(main())
