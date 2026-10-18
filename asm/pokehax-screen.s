@ Frame-driven, RAM/VRAM-only CLI_RUN_BUFFER_SCRIPT test for FRLG.
@
@ Client_RunBufferScript calls this once per frame as
@     u32 (*func)(u32 *param, struct SaveBlock2 *sav2, struct SaveBlock1 *sav1)
@ The state lives in *param.  Every invocation is bounded: initialize/show,
@ wait for an A release, wait for a new A press, restore, and return 1.
@
@ The display path mirrors asm/resident/overlay.inc: game-owned OAM and palette
@ buffers are updated before VBlank, while OBJ tiles are written directly with
@ 32-bit stores.  Interrupts are never disabled.  No save or flash address is
@ read or written.

    .arm
    .text
    .global _start
_start:
    stmdb   sp!, {r4-r11, lr}
    mov     r10, r0
    ldr     r1, [r10]
    ldr     r2, .Lstate_release
    cmp     r1, r2
    beq     .Lrelease
    ldr     r2, .Lstate_press
    cmp     r1, r2
    beq     .Lpress

    @ Backups live after the 1024-byte wire image in gDecompressionBuffer.
    adr     r11, _start
    add     r11, r11, #0x400

    ldr     r4, .Lreg_dispcnt
    ldrh    r5, [r4]
    str     r5, [r11], #4

    ldr     r4, .Lp_overlay_pal
    sub     r5, r4, #2
    ldr     r6, [r5]
    str     r6, [r11], #4
    sub     r5, r5, #0x400
    ldr     r6, [r5]
    str     r6, [r11], #4

    ldr     r6, .Lobj_to_bg
    sub     r5, r4, r6
    ldrh    r6, [r5]
    str     r6, [r11], #4
    sub     r5, r5, #0x400
    ldrh    r6, [r5]
    str     r6, [r11], #4

    @ Save all game OAM entries so the screen can be completely black.
    ldr     r4, .Lp_overlay_oam
    sub     r4, r4, #0x3C0
    mov     r5, #256
.Lsave_oam:
    ldr     r6, [r4], #4
    str     r6, [r11], #4
    subs    r5, r5, #1
    bne     .Lsave_oam

    @ Save OBJ tiles 996..1023 (four 8x8 tiles per 16x16 glyph).
    ldr     r4, .Ltile_base
    mov     r5, #224
.Lsave_tiles:
    ldr     r6, [r4], #4
    str     r6, [r11], #4
    subs    r5, r5, #1
    bne     .Lsave_tiles

    bl      .Lmake_tiles
    bl      .Ldraw
    ldr     r1, .Lstate_release
    str     r1, [r10]
    b       .Lreturn_zero

.Lrelease:
    bl      .Ldraw
    ldr     r4, .Lreg_keyinput
    ldrh    r5, [r4]
    tst     r5, #1
    beq     .Lreturn_zero
    ldr     r1, .Lstate_press
    str     r1, [r10]
    b       .Lreturn_zero

.Lpress:
    bl      .Ldraw
    ldr     r4, .Lreg_keyinput
    ldrh    r5, [r4]
    tst     r5, #1
    bne     .Lreturn_zero

    @ Restore every touched byte before reporting completion.
    adr     r11, _start
    add     r11, r11, #0x400
    ldr     r8, [r11], #4
    ldr     r9, [r11], #4
    ldr     r7, [r11], #4
    ldr     r6, [r11], #4
    ldr     r5, [r11], #4

    ldr     r4, .Lp_overlay_oam
    sub     r4, r4, #0x3C0
    mov     r3, #256
.Lrestore_oam:
    ldr     r2, [r11], #4
    str     r2, [r4], #4
    subs    r3, r3, #1
    bne     .Lrestore_oam

    ldr     r4, .Ltile_base
    mov     r3, #224
.Lrestore_tiles:
    ldr     r2, [r11], #4
    str     r2, [r4], #4
    subs    r3, r3, #1
    bne     .Lrestore_tiles

    ldr     r4, .Lp_overlay_pal
    sub     r3, r4, #2
    str     r9, [r3]
    sub     r3, r3, #0x400
    str     r7, [r3]
    ldr     r3, .Lobj_to_bg
    sub     r4, r4, r3
    strh    r6, [r4]
    sub     r4, r4, #0x400
    strh    r5, [r4]
    ldr     r4, .Lreg_dispcnt
    strh    r8, [r4]

    mov     r0, #1
    str     r0, [r10]
    ldmia   sp!, {r4-r11, lr}
    bx      lr

.Lreturn_zero:
    mov     r0, #0
    ldmia   sp!, {r4-r11, lr}
    bx      lr

@ Fill the 28 OBJ tiles with 3x5 glyphs scaled to 6x10 pixels.  Each complete
@ 8-pixel row is emitted as one word; VRAM never receives a byte/halfword store.
.Lmake_tiles:
    ldr     r4, .Ltile_base
    adr     r9, .Lfont
    mov     r6, #0
.Lchar_loop:
    add     r2, r9, r6, lsl #1
    ldrh    r8, [r2]
    add     r5, r4, r6, lsl #7
    mov     r7, #0
.Lrow_loop:
    ldr     r0, .Lblank_row
    mov     r1, r0
    cmp     r7, #3
    blo     .Lstore_row
    cmp     r7, #13
    bhs     .Lstore_row
    sub     r2, r7, #3
    mov     r2, r2, lsr #1
    add     r2, r2, r2, lsl #1
    mov     r3, r8, lsr r2
    tst     r3, #1
    ldrne   r2, .Lleft_pair
    subne   r0, r0, r2
    tst     r3, #2
    ldrne   r2, .Lmiddle_left
    subne   r0, r0, r2
    subne   r1, r1, #1
    tst     r3, #4
    ldrne   r2, .Lright_pair
    subne   r1, r1, r2
.Lstore_row:
    cmp     r7, #8
    addlo   r2, r5, r7, lsl #2
    subhs   r2, r7, #8
    addhs   r2, r5, r2, lsl #2
    addhs   r2, r2, #64
    str     r0, [r2]
    str     r1, [r2, #32]
    add     r7, r7, #1
    cmp     r7, #16
    blo     .Lrow_loop
    add     r6, r6, #1
    cmp     r6, #7
    blo     .Lchar_loop
    bx      lr

@ Refresh game-owned display buffers every frame.  Mystery Gift/VBlank performs
@ the actual OAM and palette transfer, exactly as for the resident overlay.
.Ldraw:
    ldr     r4, .Lreg_dispcnt
    ldr     r5, .Lobj_only_dispcnt
    strh    r5, [r4]

    ldr     r4, .Lp_overlay_pal
    ldr     r5, .Lpal_black_white
    str     r5, [r4, #-2]
    sub     r6, r4, #0x400
    str     r5, [r6, #-2]
    ldr     r6, .Lobj_to_bg
    sub     r4, r4, r6
    mov     r5, #0
    strh    r5, [r4]
    sub     r4, r4, #0x400
    strh    r5, [r4]

    @ Hide all game sprites, then use the overlay's proven slots 120..126.
    ldr     r4, .Lp_overlay_oam
    sub     r4, r4, #0x3C0
    mov     r5, #128
    mov     r6, #160
    mov     r7, #0
.Lhide_oam:
    strh    r6, [r4]
    strh    r7, [r4, #2]
    str     r7, [r4, #4]
    add     r4, r4, #8
    subs    r5, r5, #1
    bne     .Lhide_oam

    ldr     r4, .Lp_overlay_oam
    mov     r5, #0
.Lshow_char:
    mov     r6, #72
    strh    r6, [r4]
    mov     r6, r5, lsl #4
    add     r6, r6, #64
    orr     r6, r6, #0x4000
    strh    r6, [r4, #2]
    ldr     r6, .Lattr2_base
    add     r6, r6, r5, lsl #2
    strh    r6, [r4, #4]
    strh    r7, [r4, #6]
    add     r4, r4, #8
    add     r5, r5, #1
    cmp     r5, #7
    blo     .Lshow_char
    bx      lr

    .align  2
.Lfont:
    .hword  0x13EF, 0x7B6F, 0x5AED, 0x72CF
    .hword  0x5BED, 0x5BEA, 0x5AAD
    .align  2
.Lstate_release:
    .word   0x50485801
.Lstate_press:
    .word   0x50485802
.Lreg_dispcnt:
    .word   0x04000000
.Lreg_keyinput:
    .word   0x04000130
.Ltile_base:
    .word   0x06017C80
.Lp_overlay_oam:
    .word   0x030026C8
.Lp_overlay_pal:
    .word   0x020379D6
.Lobj_to_bg:
    .word   0x000003E2
.Lobj_only_dispcnt:
    .word   0x00001040
.Lpal_black_white:
    .word   0x7FFF0000
.Lattr2_base:
    .word   0x0000F3E4
.Lblank_row:
    .word   0x22222222
.Lleft_pair:
    .word   0x01100000
.Lmiddle_left:
    .word   0x10000000
.Lright_pair:
    .word   0x00000110
