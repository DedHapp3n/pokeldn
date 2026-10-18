@ Safe CLI_RUN_BUFFER_SCRIPT display test for FireRed/LeafGreen.
@
@ Uses only GBA I/O, palette, OAM, VRAM, stack, and the unused tail of
@ gDecompressionBuffer. The OBJ tiles/OAM entries and display state touched by
@ the overlay are restored before returning. No save block or cartridge flash
@ address is read or written.
@
@ Client_RunBufferScript calls gDecompressionBuffer as
@     u32 (*func)(u32 *param, struct SaveBlock2 *sav2, struct SaveBlock1 *sav1)
@ r0 therefore points at the result word sent back by CLI_LOAD_TOSS_RESPONSE.

    .arm
    .text
    .global _start
_start:
    stmdb   sp!, {r4-r11, lr}
    mov     r10, r0                 @ result word
    adr     r11, _start
    add     r11, r11, #0x400        @ backup after the 1024-byte wire image

    ldr     r4, =0x04000208         @ REG_IME
    ldrh    r5, [r4]
    strh    r5, [r11], #2
    mov     r6, #0
    strh    r6, [r4]                @ keep OAM stable while the overlay is up

    ldr     r4, =0x04000000         @ REG_DISPCNT
    ldrh    r5, [r4]
    strh    r5, [r11], #2
    ldr     r4, =0x05000000         @ backdrop color
    ldrh    r5, [r4]
    strh    r5, [r11], #2
    ldr     r4, =0x050003E2         @ OBJ palette 15, color 1
    ldrh    r5, [r4]
    strh    r5, [r11], #2

    ldr     r4, =0x070003C0         @ OAM entries 120..126
    mov     r5, r11
    mov     r6, #14                 @ 7 entries * 8 bytes
1:  ldmia   r4!, {r7}
    stmia   r5!, {r7}
    subs    r6, r6, #1
    bne     1b

    ldr     r4, =0x06017900         @ OBJ tiles 968..1023
    mov     r6, #0x1C0              @ 1792 bytes / 4
2:  ldmia   r4!, {r7}
    stmia   r5!, {r7}
    subs    r6, r6, #1
    bne     2b

    ldr     r4, =0x04000000
    ldr     r5, =0x00001040         @ OBJ enabled, 1D mapping, all BGs off
    strh    r5, [r4]
    ldr     r4, =0x05000000
    mov     r5, #0
    strh    r5, [r4]                @ black backdrop
    ldr     r4, =0x050003E2
    ldr     r5, =0x00007FFF
    strh    r5, [r4]                @ white text

    ldr     r4, =0x06017900
    mov     r5, #0
    mov     r6, #0x1C0
3:  stmia   r4!, {r5}
    subs    r6, r6, #1
    bne     3b

    ldr     r4, =0x070003C0
    mov     r5, #0                   @ character index
4:  ldr     r6, =0x00008040          @ tall OBJ, y = 64
    strh    r6, [r4]
    mov     r6, r5, lsl #4
    add     r6, r6, #64
    orr     r6, r6, #0x4000          @ size 1: 16 x 32
    strh    r6, [r4, #2]
    mov     r6, r5, lsl #3
    add     r6, r6, #960
    add     r6, r6, #8
    orr     r6, r6, #0xF000          @ OBJ palette 15
    strh    r6, [r4, #4]
    mov     r6, #0
    strh    r6, [r4, #6]
    add     r4, r4, #8
    add     r5, r5, #1
    cmp     r5, #7
    blo     4b

    adr     r4, font
    mov     r5, #0                   @ character
char_loop:
    mov     r6, #0                   @ row
row_loop:
    ldrb    r7, [r4], #1
    mov     r8, #0                   @ column
col_loop:
    rsb     r9, r8, #4
    mov     r9, r7, lsr r9
    tst     r9, #1
    beq     next_col
    mov     r1, #0                   @ scale y
scale_y:
    mov     r2, #0                   @ scale x
scale_x:
    add     r3, r8, r8, lsl #1       @ x = column * 3 + scale x
    add     r3, r3, r2
    add     r9, r6, r6, lsl #1       @ y = row * 3 + scale y + 5
    add     r9, r9, r1
    add     r9, r9, #5
    mov     r12, r9, lsr #3
    add     r12, r12, r12            @ tile row * 2
    add     r12, r12, r3, lsr #3
    mov     r12, r12, lsl #5
    and     r9, r9, #7
    add     r12, r12, r9, lsl #2
    mov     r9, r3, lsr #1
    and     r9, r9, #3
    add     r12, r12, r9
    mov     r9, r5, lsl #8           @ 8 tiles / character
    add     r12, r12, r9
    ldr     r9, =0x06017900
    add     r12, r12, r9
    ldrb    r9, [r12]
    tst     r3, #1
    orreq   r9, r9, #1
    orrne   r9, r9, #0x10
    strb    r9, [r12]
    add     r2, r2, #1
    cmp     r2, #3
    blo     scale_x
    add     r1, r1, #1
    cmp     r1, #3
    blo     scale_y
next_col:
    add     r8, r8, #1
    cmp     r8, #5
    blo     col_loop
    add     r6, r6, #1
    cmp     r6, #7
    blo     row_loop
    add     r5, r5, #1
    cmp     r5, #7
    blo     char_loop

    ldr     r4, =0x04000130          @ REG_KEYINPUT, bit 0 is A (active low)
wait_release:
    ldrh    r5, [r4]
    tst     r5, #1
    beq     wait_release
wait_press:
    ldrh    r5, [r4]
    tst     r5, #1
    bne     wait_press

    ldr     r4, =0x04000000
    mov     r5, #0x80                @ forced blank during restoration
    strh    r5, [r4]
    adr     r11, _start
    add     r11, r11, #0x400
    ldrh    r8, [r11], #2            @ saved IME
    ldrh    r9, [r11], #2            @ saved DISPCNT
    ldrh    r6, [r11], #2            @ saved backdrop
    ldrh    r7, [r11], #2            @ saved OBJ color

    ldr     r4, =0x070003C0
    mov     r5, #14
5:  ldmia   r11!, {r12}
    stmia   r4!, {r12}
    subs    r5, r5, #1
    bne     5b
    ldr     r4, =0x06017900
    mov     r5, #0x1C0
6:  ldmia   r11!, {r12}
    stmia   r4!, {r12}
    subs    r5, r5, #1
    bne     6b

    ldr     r4, =0x05000000
    strh    r6, [r4]
    ldr     r4, =0x050003E2
    strh    r7, [r4]
    ldr     r4, =0x04000000
    strh    r9, [r4]
    ldr     r4, =0x04000208
    strh    r8, [r4]

    mov     r0, #1
    str     r0, [r10]                @ success returned through Mystery Gift
    ldmia   sp!, {r4-r11, lr}
    bx      lr

font:
    .byte   0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10 @ P
    .byte   0x0E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E @ O
    .byte   0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11 @ K
    .byte   0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x1F @ E
    .byte   0x11, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11 @ H
    .byte   0x0E, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11 @ A
    .byte   0x11, 0x11, 0x0A, 0x04, 0x0A, 0x11, 0x11 @ X
    .align  2
