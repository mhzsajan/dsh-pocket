#!/usr/bin/env python3
"""Build a tiny static AArch64 ELF probe binary (no cross toolchain required).

Writes app/assets/probe/hello-arm64 (ELF64, EM_AARCH64, ET_EXEC, static, syscalls only):
    write(1, "PROBE_STATIC_OK\\n", 16); exit(0)
The message address is built with movz/movk (absolute, fixed load address 0x400000)
to avoid any PC-relative encoding subtleties.
"""
import os
import struct

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "assets", "probe", "hello-arm64")

MSG = b"PROBE_STATIC_OK\n"
EHDR = 64
PHDR = 56
CODE_OFF = EHDR + PHDR
BASE = 0x400000
ENTRY = BASE + CODE_OFF

N_INSNS = 10          # code instructions before the message bytes
MSG_ADDR = ENTRY + 4 * N_INSNS


def movz(rd, imm16, shift=0):
    return 0xD2800000 | ((shift // 16) << 21) | ((imm16 & 0xFFFF) << 5) | rd


def movk(rd, imm16, shift=0):
    return 0xF2800000 | ((shift // 16) << 21) | ((imm16 & 0xFFFF) << 5) | rd


def build():
    code = [
        movz(0, 1),                          # mov x0, #1  (stdout)
        movz(1, MSG_ADDR & 0xFFFF),          # movz x1, #msg_lo
        movk(1, (MSG_ADDR >> 16) & 0xFFFF, 16),  # movk x1, #msg_hi, lsl #16
        movz(2, len(MSG)),                   # mov x2, #len
        movz(8, 64),                         # mov x8, #64 (write)
        0xD4000001,                          # svc #0
        movz(0, 0),                          # mov x0, #0
        movz(8, 93),                         # mov x8, #93 (exit)
        0xD4000001,                          # svc #0
        0xD503201F,                          # nop (padding)
    ]
    assert len(code) == N_INSNS
    blob = b"".join(struct.pack("<I", w) for w in code) + MSG

    total = CODE_OFF + len(blob)
    ehdr = struct.pack(
        "<16sHHIQQQIHHHHHH",
        b"\x7fELF\x02\x01\x01\x00" + b"\x00" * 8,
        2, 0xB7, 1,       # ET_EXEC, EM_AARCH64, EV_CURRENT
        ENTRY, EHDR, 0, 0,
        64, PHDR, 1, 0, 0, 0,
    )
    # one PT_LOAD (R+X): file offset 0 -> vaddr BASE
    phdr = struct.pack("<IIQQQQQQ", 1, 5, 0, BASE, BASE, total, total, 0x1000)
    return ehdr + phdr + blob


def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    data = build()
    with open(OUT, "wb") as f:
        f.write(data)
    os.chmod(OUT, 0o755)
    print("wrote", OUT, len(data), "bytes; entry", hex(ENTRY), "msg", hex(MSG_ADDR))


if __name__ == "__main__":
    main()
