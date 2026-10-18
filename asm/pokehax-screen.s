@ Interactive, frame-driven, RAM/VRAM-only CLI_RUN_BUFFER_SCRIPT for FRLG.
@ Uses the resident overlay's game-owned OAM/palette buffers and word-only VRAM
@ stores. OAM slot 125 is deliberately unused: P/O/K/E/H/A/X occupy
@ 120/121/122/123/124/127/126. Every call returns; only EXIT returns 1.

    .arm
    .text
    .global _start
_start:
    add     r3, pc, #1
    bx      r3

    .thumb
.Lthumb:
    push    {r4-r7, lr}
    mov     r7, r0                  @ &client->param
    ldr     r6, =0x0201E000
    ldr     r1, [r7]
    ldr     r2, =0x5048584D
    cmp     r1, r2
    beq     .Lactive

    @ State header: held keys, page, selection, frame, colour.
    ldr     r4, =0x04000130
    ldrh    r5, [r4]
    mvn     r5, r5
    mov     r4, #0xC3
    and     r5, r4
    str     r5, [r6]
    mov     r5, #0
    str     r5, [r6, #4]
    str     r5, [r6, #8]
    str     r5, [r6, #12]
    str     r5, [r6, #16]

    add     r6, #20                 @ display backups follow the state
    ldr     r4, =0x04000000
    ldrh    r5, [r4]
    str     r5, [r6]
    add     r6, #4
    ldr     r4, =0x020379D6
    sub     r4, #2                  @ OBJ colours 0..3, faded
    ldr     r5, [r4]
    str     r5, [r6]
    add     r6, #4
    ldr     r5, [r4, #4]
    str     r5, [r6]
    add     r6, #4
    mov     r3, #4
    lsl     r3, r3, #8
    sub     r4, r3                  @ OBJ colours 0..3, unfaded
    ldr     r5, [r4]
    str     r5, [r6]
    add     r6, #4
    ldr     r5, [r4, #4]
    str     r5, [r6]
    add     r6, #4
    ldr     r4, =0x020379D6
    sub     r4, #2
    mov     r3, #0xF8
    lsl     r3, r3, #2
    sub     r4, r3                  @ BG colour 0, faded
    ldrh    r5, [r4]
    str     r5, [r6]
    add     r6, #4
    mov     r3, #4
    lsl     r3, r3, #8
    sub     r4, r3                  @ BG colour 0, unfaded
    ldrh    r5, [r4]
    str     r5, [r6]
    add     r6, #4

    ldr     r4, =0x030026C8
    mov     r3, #15
    lsl     r3, r3, #6
    sub     r4, r3                  @ gMain.oamBuffer[0]
    mov     r5, #128
    lsl     r5, r5, #1             @ 256 words
.Lsave_oam:
    ldr     r3, [r4]
    str     r3, [r6]
    add     r4, #4
    add     r6, #4
    sub     r5, #1
    bne     .Lsave_oam
    ldr     r4, =0x06017C00
    mov     r5, #240                @ 30 tiles
.Lsave_tiles:
    ldr     r3, [r4]
    str     r3, [r6]
    add     r4, #4
    add     r6, #4
    sub     r5, #1
    bne     .Lsave_tiles
    bl      .Lmake_font
    ldr     r6, =0x0201E000
    ldr     r1, =0x5048584D
    str     r1, [r7]
    bl      .Ldraw
    b       .Lzero

.Lactive:
    ldr     r5, [r6, #12]
    add     r5, #1
    str     r5, [r6, #12]
    ldr     r4, =0x04000130
    ldrh    r5, [r4]
    mvn     r5, r5
    mov     r4, #0xC3
    and     r5, r4
    ldr     r3, [r6]
    mov     r4, r5
    bic     r4, r3                  @ new keys only
    str     r5, [r6]
    ldr     r5, [r6, #4]           @ page 0 menu, 1 frame, 2 rng, 3 colour
    cmp     r5, #0
    beq     .Lmenu_input
    mov     r3, #2
    tst     r4, r3                  @ B returns from a subpage
    beq     .Lcolour_input
    mov     r5, #0
    str     r5, [r6, #4]
    b       .Lredraw
.Lcolour_input:
    cmp     r5, #3
    bne     .Lredraw
    mov     r3, #1
    tst     r4, r3                  @ A cycles the background
    beq     .Lredraw
    ldr     r5, [r6, #16]
    add     r5, #1
    mov     r3, #3
    and     r5, r3
    str     r5, [r6, #16]
    b       .Lredraw

.Lmenu_input:
    ldr     r5, [r6, #8]
    mov     r3, #0x40
    tst     r4, r3
    beq     .Ldown
    sub     r5, #1
    mov     r3, #3
    and     r5, r3
.Ldown:
    mov     r3, #0x80
    tst     r4, r3
    beq     .Lchoose
    add     r5, #1
    mov     r3, #3
    and     r5, r3
.Lchoose:
    str     r5, [r6, #8]
    mov     r3, #1
    tst     r4, r3
    beq     .Lredraw
    cmp     r5, #3
    beq     .Lrestore
    add     r5, #1
    str     r5, [r6, #4]
.Lredraw:
    bl      .Ldraw
.Lzero:
    mov     r0, #0
    pop     {r4-r7}
    pop     {r1}
    bx      r1
    .ltorg

.Lrestore:
    add     r6, #20
    ldr     r2, [r6]
    add     r6, #4
    ldr     r4, =0x020379D6
    sub     r4, #2
    ldr     r5, [r6]
    str     r5, [r4]
    add     r6, #4
    ldr     r5, [r6]
    str     r5, [r4, #4]
    add     r6, #4
    mov     r3, #4
    lsl     r3, r3, #8
    sub     r4, r3
    ldr     r5, [r6]
    str     r5, [r4]
    add     r6, #4
    ldr     r5, [r6]
    str     r5, [r4, #4]
    add     r6, #4
    ldr     r4, =0x020379D6
    sub     r4, #2
    mov     r3, #0xF8
    lsl     r3, r3, #2
    sub     r4, r3
    ldr     r5, [r6]
    strh    r5, [r4]
    add     r6, #4
    mov     r3, #4
    lsl     r3, r3, #8
    sub     r4, r3
    ldr     r5, [r6]
    strh    r5, [r4]
    add     r6, #4
    ldr     r4, =0x030026C8
    mov     r3, #15
    lsl     r3, r3, #6
    sub     r4, r3
    mov     r5, #128
    lsl     r5, r5, #1
.Lrestore_oam:
    ldr     r3, [r6]
    str     r3, [r4]
    add     r6, #4
    add     r4, #4
    sub     r5, #1
    bne     .Lrestore_oam
    ldr     r4, =0x06017C00
    mov     r5, #240
.Lrestore_tiles:
    ldr     r3, [r6]
    str     r3, [r4]
    add     r6, #4
    add     r4, #4
    sub     r5, #1
    bne     .Lrestore_tiles
    ldr     r4, =0x04000000
    strh    r2, [r4]
    mov     r0, #1
    str     r0, [r7]
    pop     {r4-r7}
    pop     {r1}
    bx      r1
    .ltorg

@ Thirty packed 3x5 glyphs become 8x8 4bpp tiles. VRAM gets words only.
.Lmake_font:
    push    {r4-r7, lr}
    ldr     r4, =0x06017C00
    mov     r6, #0
.Lfont_char:
    adr     r3, .Lfont
    mov     r2, r6
    lsl     r2, r2, #1
    ldrh    r1, [r3, r2]
    mov     r5, r6
    lsl     r5, r5, #5
    add     r5, r4
    mov     r7, #0
.Lfont_row:
    ldr     r0, =0x22222222
    cmp     r7, #1
    blo     .Lfont_store
    cmp     r7, #6
    bhs     .Lfont_store
    mov     r2, r7
    sub     r2, #1
    mov     r3, r2
    lsl     r2, r2, #1
    add     r2, r3
    mov     r3, r1
    lsr     r3, r2
    mov     r2, #1
    tst     r3, r2
    beq     .Lfont_bit1
    ldr     r2, =0x00000100
    sub     r0, r2
.Lfont_bit1:
    mov     r2, #2
    tst     r3, r2
    beq     .Lfont_bit2
    ldr     r2, =0x00001000
    sub     r0, r2
.Lfont_bit2:
    mov     r2, #4
    tst     r3, r2
    beq     .Lfont_store
    ldr     r2, =0x00010000
    sub     r0, r2
.Lfont_store:
    mov     r2, r7
    lsl     r2, r2, #2
    str     r0, [r5, r2]
    add     r7, #1
    cmp     r7, #8
    blo     .Lfont_row
    add     r6, #1
    cmp     r6, #30
    blo     .Lfont_char
    pop     {r4-r7}
    pop     {r0}
    bx      r0
    .ltorg

.Ldraw:
    push    {r4-r7, lr}
    ldr     r6, =0x0201E000
    ldr     r4, =0x04000000
    ldr     r5, =0x00001040
    strh    r5, [r4]
    ldr     r5, [r6, #16]
    adr     r4, .Lcolours
    lsl     r5, r5, #1
    ldrh    r7, [r4, r5]
    ldr     r4, =0x020379D6
    sub     r4, #2
    strh    r7, [r4]
    ldr     r5, =0x00007FFF
    strh    r5, [r4, #2]
    strh    r7, [r4, #4]
    mov     r3, #4
    lsl     r3, r3, #8
    sub     r4, r3
    strh    r7, [r4]
    strh    r5, [r4, #2]
    strh    r7, [r4, #4]
    ldr     r4, =0x020379D6
    sub     r4, #2
    mov     r3, #0xF8
    lsl     r3, r3, #2
    sub     r4, r3
    strh    r7, [r4]
    mov     r3, #4
    lsl     r3, r3, #8
    sub     r4, r3
    strh    r7, [r4]

    ldr     r4, =0x030026C8
    mov     r3, #15
    lsl     r3, r3, #6
    sub     r4, r3
    mov     r5, #128
    mov     r6, #160
    mov     r0, #0
.Lhide_oam:
    strh    r6, [r4]
    strh    r0, [r4, #2]
    str     r0, [r4, #4]
    add     r4, #8
    sub     r5, #1
    bne     .Lhide_oam

    @ Matrix 30 magnifies the title; entry 125 remains hidden.
    ldr     r4, =0x030026C8
    mov     r5, #0x80
    strh    r5, [r4, #6]
    strh    r0, [r4, #14]
    strh    r0, [r4, #22]
    strh    r5, [r4, #30]
    adr     r3, .Ltitle_slots
    mov     r5, #0
.Ltitle:
    ldrb    r6, [r3, r5]
    ldr     r4, =0x030026C8
    mov     r2, #15
    lsl     r2, r2, #6
    sub     r4, r2
    lsl     r6, r6, #3
    add     r4, r6
    mov     r6, #0xC4
    lsl     r6, r6, #2           @ 0x310: affine, double, y=16
    strh    r6, [r4]
    mov     r6, r5
    lsl     r6, r6, #4
    add     r6, #64
    mov     r2, #0xF0
    lsl     r2, r2, #6           @ affine matrix 30
    orr     r6, r2
    strh    r6, [r4, #2]
    ldr     r6, =0x0000F3E0
    add     r6, r5
    add     r6, #1
    strh    r6, [r4, #4]
    add     r5, #1
    cmp     r5, #7
    blo     .Ltitle

    ldr     r6, =0x0201E000
    ldr     r4, [r6, #4]
    cmp     r4, #0
    bne     .Lsubpage
    mov     r0, #80
    mov     r1, #88
    mov     r2, #56
    adr     r3, .Lstrings
    bl      .Ltext
    mov     r0, #85
    mov     r2, #72
    adr     r3, .Lstrings
    add     r3, #6
    bl      .Ltext
    mov     r0, #88
    mov     r2, #88
    adr     r3, .Lstrings
    add     r3, #10
    bl      .Ltext
    mov     r0, #93
    mov     r2, #104
    adr     r3, .Lstrings
    add     r3, #16
    bl      .Ltext
    mov     r0, #79
    mov     r1, #72
    ldr     r6, =0x0201E000
    ldr     r5, [r6, #8]
    mov     r2, r5
    lsl     r2, r2, #4
    add     r2, #56
    adr     r3, .Lstrings
    add     r3, #21
    bl      .Ltext
    b       .Ldraw_done
.Lsubpage:
    cmp     r4, #1
    bne     .Lnot_frame
    adr     r3, .Lstrings
    b       .Lpage_label
.Lnot_frame:
    cmp     r4, #2
    bne     .Lcolour_page
    adr     r3, .Lstrings
    add     r3, #6
    b       .Lpage_label
.Lcolour_page:
    adr     r3, .Lstrings
    add     r3, #10
.Lpage_label:
    mov     r0, #80
    mov     r1, #64
    mov     r2, #56
    bl      .Ltext
    ldr     r6, =0x0201E000
    ldr     r4, [r6, #4]
    cmp     r4, #1
    bne     .Lnot_frame_value
    ldr     r0, [r6, #12]
    b       .Lpage_value
.Lnot_frame_value:
    cmp     r4, #2
    bne     .Lcolour_value
    ldr     r5, =0x03004220
    ldr     r0, [r5]
    b       .Lpage_value
.Lcolour_value:
    ldr     r0, [r6, #16]
    mov     r1, #90
    mov     r2, #96
    mov     r3, #72
    bl      .Lone_hex
    b       .Ldraw_done
.Lpage_value:
    mov     r1, #90
    mov     r2, #96
    mov     r3, #72
    bl      .Lhex
.Ldraw_done:
    pop     {r4-r7}
    pop     {r0}
    bx      r0
    .ltorg

@ r0 slot, r1 x, r2 y, r3 zero-terminated glyph indices.
.Ltext:
    ldrb    r5, [r3]
    add     r3, #1
    cmp     r5, #0
    beq     .Ltext_done
    ldr     r4, =0x030026C8
    mov     r6, #15
    lsl     r6, r6, #6
    sub     r4, r6
    mov     r6, r0
    lsl     r6, r6, #3
    add     r4, r6
    strh    r2, [r4]
    strh    r1, [r4, #2]
    ldr     r6, =0x0000F3E0
    add     r6, r5
    strh    r6, [r4, #4]
    add     r0, #1
    add     r1, #8
    b       .Ltext
.Ltext_done:
    bx      lr

@ r0 value, r1 slot, r2 x, r3 y.
.Lhex:
    push    {r4-r7, lr}
    adr     r4, .Lhex_glyphs
    mov     r5, #28
.Lhex_digit:
    mov     r6, r0
    lsr     r6, r5
    mov     r7, #15
    and     r6, r7
    ldrb    r6, [r4, r6]
    ldr     r7, =0x030026C8
    mov     r4, #15
    lsl     r4, r4, #6
    sub     r7, r4
    mov     r4, r1
    lsl     r4, r4, #3
    add     r7, r4
    strh    r3, [r7]
    strh    r2, [r7, #2]
    ldr     r4, =0x0000F3E0
    add     r6, r4
    strh    r6, [r7, #4]
    adr     r4, .Lhex_glyphs
    add     r1, #1
    add     r2, #8
    sub     r5, #4
    bpl     .Lhex_digit
    pop     {r4-r7}
    pop     {r0}
    bx      r0

.Lone_hex:
    push    {r4-r7, lr}
    mov     r4, #15
    and     r0, r4
    adr     r4, .Lhex_glyphs
    ldrb    r4, [r4, r0]
    ldr     r7, =0x030026C8
    mov     r6, #15
    lsl     r6, r6, #6
    sub     r7, r6
    lsl     r1, r1, #3
    add     r7, r1
    strh    r3, [r7]
    strh    r2, [r7, #2]
    ldr     r5, =0x0000F3E0
    add     r5, r4
    strh    r5, [r7, #4]
    pop     {r4-r7}
    pop     {r0}
    bx      r0

    .align  2
.Lfont:
    .hword  0x0000, 0x13EF, 0x7B6F, 0x5AED, 0x72CF, 0x5BED
    .hword  0x5BEA, 0x5AAD, 0x12CF, 0x5AEB, 0x5BFD, 0x5FFD
    .hword  0x6B4E, 0x724F, 0x7249, 0x7497, 0x2497, 0x7B6F
    .hword  0x749A, 0x73E7, 0x79A7, 0x49ED, 0x79CF, 0x7BCF
    .hword  0x4927, 0x7BEF, 0x79EF, 0x1511, 0x3AEB, 0x3B6B
.Ltitle_slots:
    .byte   120, 121, 122, 123, 124, 127, 126, 0
.Lstrings:
.Lframe_text:   .byte 8, 9, 6, 10, 4, 0
.Lrng_text:     .byte 9, 11, 12, 0
.Lcolour_text:  .byte 13, 2, 14, 2, 9, 0
.Lexit_text:    .byte 4, 7, 15, 16, 0
.Larrow_text:   .byte 27, 0
    .align  2
.Lhex_glyphs:
    .byte   17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 6, 28, 13, 29, 4, 8
    .align  2
.Lcolours:          .hword 0x0000, 0x000C, 0x0180, 0x3000
    .ltorg
