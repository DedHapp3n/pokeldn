@ POKEHAX LAB: interactive, frame-driven, RAM/VRAM-only diagnostics for FRLG.
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
    ldr     r6, =0x0201F000
    ldr     r1, [r7]
    ldr     r2, =0x5048584D
    cmp     r1, r2
    beq     .Lactive

    @ State header: held keys, page, selection, frame, colour.
    ldr     r4, =0x04000130
    ldrh    r5, [r4]
    mvn     r5, r5
    mov     r4, #0xF3
    and     r5, r4
    str     r5, [r6]
    mov     r5, #0
    str     r5, [r6, #4]
    str     r5, [r6, #8]
    str     r5, [r6, #12]
    str     r5, [r6, #16]          @ retained preset/demo index
    str     r5, [r6, #20]
    str     r5, [r6, #24]
    ldr     r5, =0x02000000
    str     r5, [r6, #28]
    mov     r5, #0
    str     r5, [r6, #56]          @ DISPLAY row
    str     r5, [r6, #64]          @ background RGB555
    str     r5, [r6, #68]          @ rainbow enabled
    str     r5, [r6, #72]          @ palette demo
    ldr     r5, =0x00007FFF
    str     r5, [r6, #60]          @ text RGB555

    add     r6, #80                 @ display backups follow the state
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
    ldr     r4, =0x06017B40
    mov     r5, #38
    lsl     r5, r5, #3
.Lsave_tiles:
    ldr     r3, [r4]
    str     r3, [r6]
    add     r4, #4
    add     r6, #4
    sub     r5, #1
    bne     .Lsave_tiles
    bl      .Lmake_font
    ldr     r6, =0x0201F000
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
    mov     r4, #0xF3
    and     r5, r4
    ldr     r3, [r6]
    mov     r4, r5
    bic     r4, r3                  @ new keys only
    str     r5, [r6]
    ldr     r5, [r6, #68]
    cmp     r5, #0
    beq     19f
    ldr     r5, [r6, #60]
    ldr     r3, =0x00000421         @ safe RGB555 walk, one step per frame
    add     r5, r3
    ldr     r3, =0x00007FFF
    and     r5, r3
    str     r5, [r6, #60]
19:
    ldr     r5, [r6, #4]           @ 0 main; 1..6 labs; 7 party detail
    cmp     r5, #0
    bne     191f
    b       .Lmenu_input
191:
    mov     r3, #2
    tst     r4, r3
    beq     .Lsub_input
    cmp     r5, #7
    bne     .Lback_main
    mov     r5, #2
    str     r5, [r6, #4]
    b       .Lredraw
.Lback_main:
    mov     r5, #0
    str     r5, [r6, #4]
    b       .Lredraw
.Lsub_input:
    cmp     r5, #2
    bne     .Lnot_party_input
    ldr     r5, [r6, #20]
    mov     r3, #0x40
    tst     r4, r3
    beq     1f
    cmp     r5, #0
    bne     0f
    mov     r5, #6
0:  sub     r5, #1
1:  mov     r3, #0x80
    tst     r4, r3
    beq     2f
    add     r5, #1
    cmp     r5, #6
    blo     2f
    mov     r5, #0
2:  str     r5, [r6, #20]
    mov     r3, #1
    tst     r4, r3
    bne     192f
    b       .Lredraw
192:
    mov     r5, #7
    str     r5, [r6, #4]
    b       .Lredraw
.Lnot_party_input:
    cmp     r5, #3
    bne     .Lnot_world_input
    ldr     r5, [r6, #24]
    mov     r3, #0x40
    tst     r4, r3
    beq     3f
    cmp     r5, #0
    beq     3f
    sub     r5, #1
3:  mov     r3, #0x80
    tst     r4, r3
    beq     4f
    cmp     r5, #15
    bhs     4f
    add     r5, #1
4:  str     r5, [r6, #24]
    b       .Lredraw
.Lnot_world_input:
    cmp     r5, #4
    bne     .Lnot_memory_input
    ldr     r5, [r6, #28]
    mov     r3, #1
    tst     r4, r3
    beq     5f
    lsr     r3, r5, #24
    cmp     r3, #2
    bne     41f
    ldr     r5, =0x03000000
    b       5f
41: ldr     r5, =0x02000000
5:  mov     r3, #0x20
    tst     r4, r3
    beq     6f
    sub     r5, #4
6:  mov     r3, #0x10
    tst     r4, r3
    beq     7f
    add     r5, #4
7:  mov     r3, #0x40
    tst     r4, r3
    beq     8f
    sub     r5, #16
8:  mov     r3, #0x80
    tst     r4, r3
    beq     9f
    add     r5, #16
9:  lsr     r3, r5, #24
    cmp     r3, #3
    beq     10f
    ldr     r3, =0x02000000
    cmp     r5, r3
    bhs     91f
    mov     r5, r3
91: ldr     r3, =0x0203FFF0
    cmp     r5, r3
    bls     11f
    mov     r5, r3
    b       11f
10: ldr     r3, =0x03000000
    cmp     r5, r3
    bhs     101f
    mov     r5, r3
101:ldr     r3, =0x03007FF0
    cmp     r5, r3
    bls     11f
    mov     r5, r3
11: str     r5, [r6, #28]
    b       .Lredraw
.Lnot_memory_input:
    cmp     r5, #5
    bne     .Lredraw
    ldr     r5, [r6, #56]
    mov     r3, #0x40
    tst     r4, r3
    beq     14f
    cmp     r5, #0
    bne     13f
    mov     r5, #8
13: sub     r5, #1
14: mov     r3, #0x80
    tst     r4, r3
    beq     15f
    add     r5, #1
    cmp     r5, #8
    blo     15f
    mov     r5, #0
15: str     r5, [r6, #56]
    cmp     r5, #6
    bne     16f
    mov     r3, #1
    tst     r4, r3
    beq     .Lredraw
    ldr     r3, [r6, #68]
    mov     r2, #1
    eor     r3, r2
    str     r3, [r6, #68]
    b       .Lredraw
16: cmp     r5, #7
    bne     .Lrgb_adjust
    mov     r3, #1
    tst     r4, r3
    beq     .Lredraw
    ldr     r3, [r6, #72]
    add     r3, #1
    mov     r2, #3
    and     r3, r2
    str     r3, [r6, #72]
    ldr     r2, =.Ldemo_pairs
    lsl     r3, r3, #3
    add     r2, r3
    ldr     r3, [r2]
    str     r3, [r6, #60]
    ldr     r3, [r2, #4]
    str     r3, [r6, #64]
    b       .Lredraw
.Lrgb_adjust:
    mov     r3, #0x30
    tst     r4, r3
    beq     .Lredraw
    cmp     r5, #3
    blo     17f
    sub     r5, #3
    mov     r7, r6
    add     r7, #64
    b       18f
17: mov     r7, r6
    add     r7, #60
18: ldr     r3, [r7]
    mov     r2, #5
    mul     r5, r2
    mov     r2, #31
    lsl     r2, r5                 @ mask for selected RGB555 channel
    mov     r1, r3
    and     r1, r2
    mov     r0, #0x20
    tst     r4, r0
    beq     181f
    cmp     r1, #0
    beq     181f
    mov     r0, #1
    lsl     r0, r5
    sub     r3, r0
181:mov     r0, #0x10
    tst     r4, r0
    beq     182f
    cmp     r1, r2
    beq     182f
    mov     r0, #1
    lsl     r0, r5
    add     r3, r0
182:str     r3, [r7]
    b       .Lredraw

.Lmenu_input:
    ldr     r5, [r6, #8]
    mov     r3, #0x40
    tst     r4, r3
    beq     .Ldown
    cmp     r5, #0
    bne     12f
    mov     r5, #7
12: sub     r5, #1
.Ldown:
    mov     r3, #0x80
    tst     r4, r3
    beq     .Lchoose
    add     r5, #1
    cmp     r5, #7
    blo     .Lchoose
    mov     r5, #0
.Lchoose:
    str     r5, [r6, #8]
    mov     r3, #1
    tst     r4, r3
    beq     .Lredraw
    cmp     r5, #6
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
    add     r6, #80
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
    ldr     r4, =0x06017B40
    mov     r5, #38
    lsl     r5, r5, #3
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

@ Thirty-eight packed 3x5 glyphs become 8x8 4bpp tiles. VRAM gets words only.
.Lmake_font:
    push    {r4-r7, lr}
    ldr     r4, =0x06017B40
    mov     r6, #0
.Lfont_char:
    ldr     r3, =.Lfont
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
    cmp     r6, #38
    blo     .Lfont_char
    pop     {r4-r7}
    pop     {r0}
    bx      r0
    .ltorg

.Ldraw:
    push    {r4-r7, lr}
    ldr     r6, =0x0201F000
    ldr     r4, =0x04000000
    ldr     r5, =0x00001040
    strh    r5, [r4]
    ldr     r5, [r6, #60]          @ text RGB555
    ldr     r7, [r6, #64]          @ background RGB555
    ldr     r4, =0x020379D6
    sub     r4, #2
    strh    r7, [r4]
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
    ldr     r3, =.Ltitle_slots
    ldr     r7, =.Ltitle_glyphs
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
    add     r6, #32
    cmp     r5, #7
    blo     13f
    add     r6, #16
13:
    mov     r2, #0xF0
    lsl     r2, r2, #6           @ affine matrix 30
    orr     r6, r2
    strh    r6, [r4, #2]
    ldrb    r6, [r7, r5]
    ldr     r2, =0x0000F3DA
    add     r6, r2
    strh    r6, [r4, #4]
    add     r5, #1
    cmp     r5, #10
    blo     .Ltitle

    b       .Lafter_title_pool
    .ltorg
.Lafter_title_pool:

    ldr     r6, =0x0201F000
    ldr     r4, [r6, #4]
    cmp     r4, #0
    bne     .Lsubpage
    ldr     r7, =.Lmain_text
    mov     r5, #0
.Lmain_line:
    mov     r0, r5
    mov     r1, #10
    mul     r0, r1
    add     r0, #9
    mov     r1, #80
    mov     r2, r5
    lsl     r2, r2, #4
    add     r2, #40
    mov     r3, r7
    bl      .Ltext
.Lskip_main:
    ldrb    r0, [r7]
    add     r7, #1
    cmp     r0, #0
    bne     .Lskip_main
    add     r5, #1
    cmp     r5, #7
    blo     .Lmain_line
    ldr     r5, [r6, #8]
    mov     r0, r5
    mov     r1, #10
    mul     r0, r1
    add     r0, #8
    mov     r1, #64
    mov     r2, r5
    lsl     r2, r2, #4
    add     r2, #40
    ldr     r3, =.Larrow_text
    bl      .Ltext
    b       .Ldraw_done
    .ltorg
.Lsubpage:
    cmp     r4, #1
    bne     20f
    b       .Ldraw_live
20:
    cmp     r4, #2
    bne     21f
    b       .Ldraw_party
21:
    cmp     r4, #3
    bne     22f
    b       .Ldraw_world
22:
    cmp     r4, #4
    bne     23f
    b       .Ldraw_memory
23:
    cmp     r4, #5
    bne     24f
    b       .Ldraw_display
24:
    cmp     r4, #6
    bne     25f
    b       .Ldraw_host
25:
    b       .Ldraw_party_detail
    .ltorg

.Ldraw_live:
    mov     r0, #20
    mov     r1, #24
    mov     r2, #40
    ldr     r3, =.Llive_text
    bl      .Ltext
    ldr     r0, [r6, #12]
    mov     r1, #26
    mov     r2, #72
    mov     r3, #40
    bl      .Lhex
    mov     r0, #34
    mov     r1, #24
    mov     r2, #56
    ldr     r3, =.Lrng_label
    bl      .Ltext
    ldr     r5, =0x03004220
    ldr     r0, [r5]
    mov     r1, #38
    mov     r2, #72
    mov     r3, #56
    bl      .Lhex
    ldr     r5, =0x02037074
    ldrb    r5, [r5, #5]
    mov     r4, #0x24
    mul     r5, r4
    ldr     r4, =0x02036E34
    add     r5, r4
    mov     r0, #46
    mov     r1, #24
    mov     r2, #72
    ldr     r3, =.Lxy_label
    bl      .Ltext
    ldrh    r0, [r5, #0x10]
    mov     r1, #48
    mov     r2, #48
    mov     r3, #72
    bl      .Lhex
    ldrh    r0, [r5, #0x12]
    mov     r1, #56
    mov     r2, #120
    mov     r3, #72
    bl      .Lhex
    mov     r0, #64
    mov     r1, #24
    mov     r2, #88
    ldr     r3, =.Lface_label
    bl      .Ltext
    ldrb    r0, [r5, #0x18]
    mov     r1, #69
    mov     r2, #72
    mov     r3, #88
    bl      .Lone_hex
    ldr     r5, =0x030022D0
    mov     r0, #70
    mov     r1, #24
    mov     r2, #104
    ldr     r3, =.Lcb1_text
    bl      .Ltext
    ldr     r0, [r5]
    mov     r1, #73
    mov     r2, #48
    mov     r3, #104
    bl      .Lhex
    mov     r0, #81
    mov     r1, #120
    mov     r2, #104
    ldr     r3, =.Lcb2_text
    bl      .Ltext
    ldr     r0, [r5, #4]
    mov     r1, #84
    mov     r2, #144
    mov     r3, #104
    bl      .Lhex
    mov     r0, #92
    mov     r1, #24
    mov     r2, #120
    ldr     r3, =.Lmap_text
    bl      .Ltext
    ldr     r5, =0x03004228
    ldr     r5, [r5]
    ldrb    r0, [r5, #4]
    mov     r1, #96
    mov     r2, #56
    mov     r3, #120
    bl      .Lhex8
    ldrb    r0, [r5, #5]
    mov     r1, #98
    mov     r2, #80
    mov     r3, #120
    bl      .Lhex8
    b       .Ldraw_done
    .ltorg

.Ldraw_party:
    mov     r5, #0
.Lparty_line:
    mov     r0, r5
    mov     r1, #17
    mul     r0, r1
    add     r0, #8
    mov     r1, #0
    mov     r2, r5
    lsl     r2, r2, #4
    add     r2, #40
    ldr     r4, [r6, #20]
    cmp     r4, r5
    bne     .Lparty_no_arrow
    ldr     r3, =.Larrow_text
    bl      .Ltext
.Lparty_no_arrow:
    mov     r0, r5
    add     r0, #1
    mov     r1, r5
    mov     r3, #17
    mul     r1, r3
    add     r1, #9
    mov     r2, #8
    mov     r3, r5
    lsl     r3, r3, #4
    add     r3, #40
    bl      .Lone_hex
    mov     r0, r5
    mov     r4, #100
    mul     r0, r4
    ldr     r4, =0x02024280
    add     r4, r0
    mov     r0, r5
    mov     r1, #17
    mul     r0, r1
    add     r0, #10
    mov     r1, #16
    mov     r2, r5
    lsl     r2, r2, #4
    add     r2, #40
    mov     r3, r4
    add     r3, #8
    mov     r7, #7
    bl      .Lgba_text
    mov     r0, r5
    mov     r1, #17
    mul     r0, r1
    add     r0, #17
    mov     r1, #72
    mov     r2, r5
    lsl     r2, r2, #4
    add     r2, #40
    ldr     r3, =.Ll_text
    bl      .Ltext
    mov     r7, r4
    add     r7, #84
    ldrb    r0, [r7]
    mov     r1, r5
    mov     r3, #17
    mul     r1, r3
    add     r1, #18
    mov     r2, #80
    mov     r3, r5
    lsl     r3, r3, #4
    add     r3, #40
    bl      .Lhex8
    mov     r0, r5
    mov     r1, #17
    mul     r0, r1
    add     r0, #20
    mov     r1, #104
    mov     r2, r5
    lsl     r2, r2, #4
    add     r2, #40
    ldr     r3, =.Lh_text
    bl      .Ltext
    ldrh    r0, [r7, #2]
    mov     r1, r5
    mov     r3, #17
    mul     r1, r3
    add     r1, #21
    mov     r2, #112
    mov     r3, r5
    lsl     r3, r3, #4
    add     r3, #40
    bl      .Lhex16
    add     r5, #1
    cmp     r5, #6
    blo     .Lparty_line
    b       .Ldraw_done
    .ltorg

.Ldraw_party_detail:
    bl      .Ldecode_mon
    ldr     r5, [r6, #20]
    mov     r4, #100
    mul     r5, r4
    ldr     r4, =0x02024280
    add     r4, r5
    mov     r0, #8
    mov     r1, #8
    mov     r2, #40
    ldr     r3, =.Lname_text
    bl      .Ltext
    mov     r0, #13
    mov     r1, #48
    mov     r2, #40
    mov     r3, r4
    add     r3, #8
    mov     r7, #7
    bl      .Lgba_text
    mov     r0, #20
    mov     r1, #8
    mov     r2, #56
    ldr     r3, =.Ldetail_text
    bl      .Ltext
    ldr     r0, [r6, #32]
    mov     r1, #24
    mov     r2, #40
    mov     r3, #56
    bl      .Lhex
    mov     r0, #32
    mov     r1, #8
    mov     r2, #72
    ldr     r3, =.Lnature_text
    bl      .Ltext
    ldr     r0, [r6, #32]
    bl      .Lnature_ptr
    mov     r3, r0
    mov     r0, #39
    mov     r1, #64
    mov     r2, #72
    bl      .Ltext
    mov     r0, #46
    mov     r1, #8
    mov     r2, #88
    ldr     r3, =.Litem_text
    bl      .Ltext
    ldr     r0, [r6, #36]
    mov     r1, #51
    mov     r2, #48
    mov     r3, #88
    bl      .Lhex16
    mov     r0, #55
    mov     r1, #96
    mov     r2, #88
    ldr     r3, =.Lab_text
    bl      .Ltext
    ldr     r0, [r6, #40]
    lsr     r0, r0, #31
    add     r0, #1
    mov     r1, #58
    mov     r2, #120
    mov     r3, #88
    bl      .Lone_hex
    mov     r0, #60
    mov     r1, #8
    mov     r2, #104
    ldr     r3, =.Liv_text
    bl      .Ltext
    ldr     r7, [r6, #40]
    mov     r5, #0
.Liv_loop:
    mov     r0, r7
    mov     r1, r5
    mov     r2, #5
    mul     r1, r2
    lsr     r0, r1
    mov     r1, #31
    and     r0, r1
    mov     r1, r5
    mov     r2, #3
    mul     r1, r2
    add     r1, #63
    mov     r2, r5
    mov     r3, #24
    mul     r2, r3
    add     r2, #32
    mov     r3, #104
    bl      .Lhex8
    add     r5, #1
    cmp     r5, #6
    blo     .Liv_loop
    mov     r0, #82
    mov     r1, #8
    mov     r2, #120
    ldr     r3, =.Lev_text
    bl      .Ltext
    mov     r4, r6
    add     r4, #44
    mov     r5, #0
.Lev_loop:
    ldrb    r0, [r4, r5]
    mov     r1, r5
    mov     r2, #3
    mul     r1, r2
    add     r1, #85
    mov     r2, r5
    mov     r3, #24
    mul     r2, r3
    add     r2, #32
    mov     r3, #120
    bl      .Lhex8
    add     r5, #1
    cmp     r5, #6
    blo     .Lev_loop
    mov     r0, #104
    mov     r1, #8
    mov     r2, #144
    ldr     r3, =.Lbback_text
    bl      .Ltext
    b       .Ldraw_done
    .ltorg

.Ldraw_world:
    ldr     r5, [r6, #24]
    mov     r4, #0x24
    mul     r5, r4
    ldr     r4, =0x02036E34
    add     r4, r5
    mov     r0, #8
    mov     r1, #8
    mov     r2, #40
    ldr     r3, =.Lworld_text
    bl      .Ltext
    ldr     r0, [r6, #24]
    mov     r1, #14
    mov     r2, #56
    mov     r3, #40
    bl      .Lhex8
    mov     r0, #16
    mov     r1, #8
    mov     r2, #56
    ldr     r3, =.Llocal_text
    bl      .Ltext
    ldrb    r0, [r4, #8]
    mov     r1, #22
    mov     r2, #56
    mov     r3, #56
    bl      .Lhex8
    mov     r0, #24
    mov     r1, #88
    mov     r2, #56
    ldr     r3, =.Lgfx_text
    bl      .Ltext
    ldrb    r0, [r4, #5]
    mov     r1, #28
    mov     r2, #120
    mov     r3, #56
    bl      .Lhex8
    mov     r0, #30
    mov     r1, #8
    mov     r2, #88
    ldr     r3, =.Lx_text
    bl      .Ltext
    ldrh    r0, [r4, #0x10]
    mov     r1, #32
    mov     r2, #24
    mov     r3, #88
    bl      .Lhex16
    mov     r0, #36
    mov     r1, #80
    mov     r2, #88
    ldr     r3, =.Ly_text
    bl      .Ltext
    ldrh    r0, [r4, #0x12]
    mov     r1, #38
    mov     r2, #96
    mov     r3, #88
    bl      .Lhex16
    mov     r0, #42
    mov     r1, #8
    mov     r2, #120
    ldr     r3, =.Lstatus_text
    bl      .Ltext
    ldrb    r0, [r4]
    mov     r1, #49
    mov     r2, #64
    mov     r3, #120
    bl      .Lhex8
    b       .Ldraw_done
    .ltorg

.Ldraw_memory:
    ldr     r7, [r6, #28]
    mov     r5, #0
.Lmemory_line:
    mov     r0, r7
    mov     r1, r5
    lsl     r1, r1, #4
    add     r1, #20
    mov     r2, #8
    mov     r3, r5
    lsl     r3, r3, #5
    add     r3, #40
    bl      .Lhex
    ldr     r0, [r7]
    mov     r1, r5
    lsl     r1, r1, #4
    add     r1, #28
    mov     r2, #80
    mov     r3, r5
    lsl     r3, r3, #5
    add     r3, #40
    bl      .Lhex
    add     r7, #4
    add     r5, #1
    cmp     r5, #4
    blo     .Lmemory_line
    b       .Ldraw_done
    .ltorg

.Ldraw_display:
    ldr     r7, =.Ldisplay_rows
    mov     r5, #0
.Ldisplay_line:
    mov     r0, r5
    mov     r1, #10
    mul     r0, r1
    add     r0, #9
    mov     r1, #40
    mov     r2, r5
    lsl     r2, r2, #4
    add     r2, #40
    mov     r3, r7
    bl      .Ltext
46: ldrb    r0, [r7]
    add     r7, #1
    cmp     r0, #0
    bne     46b
    add     r5, #1
    cmp     r5, #8
    blo     .Ldisplay_line
    ldr     r5, [r6, #56]
    mov     r0, r5
    mov     r1, #10
    mul     r0, r1
    add     r0, #8
    mov     r1, #24
    mov     r2, r5
    lsl     r2, r2, #4
    add     r2, #40
    ldr     r3, =.Larrow_text
    bl      .Ltext
    ldr     r4, [r6, #60]
    ldr     r7, [r6, #64]
    mov     r5, #0
.Ldisplay_value:
    cmp     r5, #3
    blo     47f
    mov     r0, r7
    mov     r1, r5
    sub     r1, #3
    b       48f
47: mov     r0, r4
    mov     r1, r5
48: mov     r2, #5
    mul     r1, r2
    lsr     r0, r1
    mov     r1, #31
    and     r0, r1
    mov     r1, r5
    mov     r2, #10
    mul     r1, r2
    add     r1, #16
    mov     r2, #112
    mov     r3, r5
    lsl     r3, r3, #4
    add     r3, #40
    bl      .Lhex8
    add     r5, #1
    cmp     r5, #6
    blo     .Ldisplay_value
    ldr     r0, [r6, #68]
    mov     r1, #76
    mov     r2, #112
    mov     r3, #136
    bl      .Lone_hex
    ldr     r0, [r6, #72]
    mov     r1, #86
    mov     r2, #112
    mov     r3, #152
    bl      .Lone_hex
    b       .Ldraw_done
    .ltorg

.Ldraw_host:
    mov     r0, #20
    mov     r1, #8
    mov     r2, #40
    ldr     r3, =.Lhost_guest
    bl      .Ltext
    mov     r0, #30
    mov     r2, #56
    ldr     r3, =.Lhost_console
    bl      .Ltext
    mov     r0, #50
    mov     r2, #72
    ldr     r3, =.Lhost_date
    bl      .Ltext
    mov     r0, #68
    mov     r2, #88
    ldr     r3, =.Lhost_firmware
    bl      .Ltext
    mov     r0, #90
    mov     r2, #104
    ldr     r3, =.Lhost_wrapper
    bl      .Ltext
.Ldraw_done:
    pop     {r4-r7}
    pop     {r0}
    bx      r0
    .ltorg

@ Decrypt selected party fields into scratch without ever writing the Pokemon.
.Ldecode_mon:
    push    {r4-r7, lr}
    ldr     r6, =0x0201F000
    ldr     r0, [r6, #20]
    mov     r1, #100
    mul     r0, r1
    ldr     r4, =0x02024280
    add     r4, r0
    ldr     r5, [r4]
    str     r5, [r6, #32]
    ldr     r7, [r4, #4]
    eor     r7, r5                 @ BoxPokemon XOR key
    mov     r0, r5
    bl      .Lmod24
    ldr     r3, =.Lsuborders
    lsl     r0, r0, #2
    add     r3, r0
    mov     r0, #0                 @ Growth: held item in upper half of word 0
    bl      .Lfind_sub
    mov     r1, #12
    mul     r0, r1
    add     r0, #32
    ldr     r0, [r4, r0]
    eor     r0, r7
    lsr     r0, r0, #16
    str     r0, [r6, #36]
    mov     r0, #2                 @ EV block: first six bytes
    bl      .Lfind_sub
    mov     r1, #12
    mul     r0, r1
    add     r0, #32
    mov     r2, r0
    ldr     r0, [r4, r2]
    eor     r0, r7
    str     r0, [r6, #44]
    add     r2, #4
    ldr     r0, [r4, r2]
    eor     r0, r7
    str     r0, [r6, #48]
    mov     r0, #3                 @ Misc: six IVs + ability bit in word 1
    bl      .Lfind_sub
    mov     r1, #12
    mul     r0, r1
    add     r0, #36
    ldr     r0, [r4, r0]
    eor     r0, r7
    str     r0, [r6, #40]
    pop     {r4-r7}
    pop     {r0}
    bx      r0
    .ltorg

.Lfind_sub:                         @ r0=G/A/E/M code, r3=four-entry order
    mov     r1, #0
42: ldrb    r2, [r3, r1]
    cmp     r2, r0
    beq     43f
    add     r1, #1
    b       42b
43: mov     r0, r1
    bx      lr

.Lnature_ptr:
    push    {lr}
    bl      .Lmod25
    ldr     r1, =.Lnature_table
    lsl     r0, r0, #2
    ldr     r0, [r1, r0]
    pop     {r1}
    bx      r1

@ Bounded byte-wise remainders: 256 == 16 (mod 24), 256 == 6 (mod 25).
.Lmod24:
    mov     r1, r0
    mov     r0, #0
    mov     r3, #4
44: lsl     r0, r0, #4
    mov     r2, r1
    lsr     r2, r2, #24
    add     r0, r2
45: cmp     r0, #24
    blo     46f
    sub     r0, #24
    b       45b
46: lsl     r1, r1, #8
    sub     r3, #1
    bne     44b
    bx      lr

.Lmod25:
    push    {lr}
    mov     r1, r0
    mov     r0, #0
    mov     r3, #4
47: mov     r2, r0
    lsl     r0, r0, #1
    add     r0, r2
    lsl     r0, r0, #1            @ old remainder * 6
    mov     r2, r1
    lsr     r2, r2, #24
    add     r0, r2
48: cmp     r0, #25
    blo     49f
    sub     r0, #25
    b       48b
49: lsl     r1, r1, #8
    sub     r3, #1
    bne     47b
    pop     {r1}
    bx      r1
    .ltorg

@ r0 slot, r1 x, r2 y, r3 zero-terminated glyph indices.
.Ltext:
    push    {r4-r7, lr}
.Ltext_loop:
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
    ldr     r6, =0x0000F3DA
    add     r6, r5
    strh    r6, [r4, #4]
    add     r0, #1
    add     r1, #8
    b       .Ltext_loop
.Ltext_done:
    pop     {r4-r7}
    pop     {r0}
    bx      r0

@ r0 slot, r1 x, r2 y, r3 GBA string, r7 maximum characters.
.Lgba_text:
    push    {r4-r7, lr}
    mov     r4, r0
    mov     r5, r1
    mov     r6, r2
.Lgba_loop:
    cmp     r7, #0
    beq     .Lgba_done
    ldrb    r0, [r3]
    add     r3, #1
    cmp     r0, #0xFF
    beq     .Lgba_done
    cmp     r0, #0xBB              @ FRLG uppercase A..Z
    blo     30f
    cmp     r0, #0xD4
    bhi     30f
    sub     r0, #0xBA
    b       33f
30: cmp     r0, #0xD5              @ lowercase displayed with uppercase glyphs
    blo     31f
    cmp     r0, #0xEE
    bhi     31f
    sub     r0, #0xD4
    b       33f
31: cmp     r0, #0xA1              @ digits 0..9
    blo     32f
    cmp     r0, #0xAA
    bhi     32f
    sub     r0, #0x86
    b       33f
32: mov     r0, #37                @ unsupported guest glyph: '-'
33: ldr     r1, =0x030026C8
    mov     r2, #15
    lsl     r2, r2, #6
    sub     r1, r2
    mov     r2, r4
    lsl     r2, r2, #3
    add     r1, r2
    strh    r6, [r1]
    strh    r5, [r1, #2]
    ldr     r2, =0x0000F3DA
    add     r0, r2
    strh    r0, [r1, #4]
    add     r4, #1
    add     r5, #8
    sub     r7, #1
    b       .Lgba_loop
.Lgba_done:
    pop     {r4-r7}
    pop     {r0}
    bx      r0
    .ltorg

@ r0 value, r1 slot, r2 x, r3 y.
.Lhex:
    push    {r4-r7, lr}
    mov     r5, #28
    b       .Lhex_begin
.Lhex16:
    push    {r4-r7, lr}
    mov     r5, #12
    b       .Lhex_begin
.Lhex8:
    push    {r4-r7, lr}
    mov     r5, #4
.Lhex_begin:
    ldr     r4, =.Lhex_glyphs
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
    ldr     r4, =0x0000F3DA
    add     r6, r4
    strh    r6, [r7, #4]
    ldr     r4, =.Lhex_glyphs
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
    ldr     r4, =.Lhex_glyphs
    ldrb    r4, [r4, r0]
    ldr     r7, =0x030026C8
    mov     r6, #15
    lsl     r6, r6, #6
    sub     r7, r6
    lsl     r1, r1, #3
    add     r7, r1
    strh    r3, [r7]
    strh    r2, [r7, #2]
    ldr     r5, =0x0000F3DA
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
    .hword  0x5B6A, 0x5FFD, 0x5492, 0x79CF, 0x5B6F, 0x0202
    .hword  0x2D24, 0x0088
.Ltitle_slots:
    .byte   120, 121, 122, 123, 124, 127, 126, 116, 117, 118
.Ltitle_glyphs:
    .byte   1, 2, 3, 4, 5, 6, 7, 14, 6, 28
.Lmain_text:
    .byte   14,15,30,4,0
    .byte   1,6,9,16,32,0
    .byte   31,2,9,14,29,0
    .byte   10,4,10,2,9,32,0
    .byte   29,15,33,1,14,6,32,0
    .byte   5,2,33,16,0
    .byte   4,7,15,16,0
.Llive_text:   .byte 8,9,6,10,4,35,0
.Lrng_label:   .byte 9,11,12,35,0
.Lxy_label:    .byte 7,32,35,0
.Lface_label:  .byte 8,6,13,4,35,0
.Lcb_label:    .byte 13,28,35,0
.Lcb1_text:    .byte 13,17,35,0
.Lcb2_text:    .byte 13,18,35,0
.Lmap_text:    .byte 10,6,1,35,0
.Ldetail_text: .byte 1,15,29,35,0
.Lworld_text:  .byte 2,28,36,4,13,16,35,0
.Llocal_text:  .byte 14,2,13,6,14,35,0
.Lgfx_text:    .byte 12,8,7,35,0
.Lx_text:      .byte 7,35,0
.Ly_text:      .byte 32,35,0
.Lstatus_text: .byte 33,16,6,16,34,33,35,0
.Ldisplay_text:.byte 13,2,14,2,9,35,0
.Ldisplay_rows:
    .byte 16,4,7,16,37,9,0
    .byte 16,4,7,16,37,12,0
    .byte 16,4,7,16,37,28,0
    .byte 28,6,13,3,37,9,0
    .byte 28,6,13,3,37,12,0
    .byte 28,6,13,3,37,28,0
    .byte 9,6,15,11,28,2,31,0
    .byte 29,4,10,2,0
.Lname_text:   .byte 11,6,10,4,35,0
.Lnature_text: .byte 11,6,16,34,9,4,35,0
.Litem_text:   .byte 15,16,4,10,35,0
.Lab_text:     .byte 6,28,35,0
.Liv_text:     .byte 15,30,35,0
.Lev_text:     .byte 4,30,35,0
.Lbback_text:  .byte 28,37,28,6,13,3,0
.Ll_text:      .byte 14,0
.Lh_text:      .byte 5,0
.Lhost_guest:  .byte 12,34,4,33,16,37,12,28,6,0
.Lhost_console:.byte 13,2,11,33,2,14,4,37,11,2,16,37,4,7,1,2,33,4,29,0
.Lhost_date:   .byte 29,6,16,4,37,11,2,16,37,4,7,1,2,33,4,29,0
.Lhost_firmware:.byte 8,15,9,10,31,6,9,4,37,11,2,16,37,4,7,1,2,33,4,29,0
.Lhost_wrapper:.byte 31,9,6,1,1,4,9,37,11,2,16,37,4,7,1,2,33,4,29,0
.Lnat_hardy:   .byte 5,6,9,29,32,0
.Lnat_lonely:  .byte 14,2,11,4,14,32,0
.Lnat_brave:   .byte 28,9,6,30,4,0
.Lnat_adamant: .byte 6,29,6,10,6,11,16,0
.Lnat_naughty: .byte 11,6,34,12,5,16,32,0
.Lnat_bold:    .byte 28,2,14,29,0
.Lnat_docile:  .byte 29,2,13,15,14,4,0
.Lnat_relaxed: .byte 9,4,14,6,7,4,29,0
.Lnat_impish:  .byte 15,10,1,15,33,5,0
.Lnat_lax:     .byte 14,6,7,0
.Lnat_timid:   .byte 16,15,10,15,29,0
.Lnat_hasty:   .byte 5,6,33,16,32,0
.Lnat_serious: .byte 33,4,9,15,2,34,33,0
.Lnat_jolly:   .byte 36,2,14,14,32,0
.Lnat_naive:   .byte 11,6,15,30,4,0
.Lnat_modest:  .byte 10,2,29,4,33,16,0
.Lnat_mild:    .byte 10,15,14,29,0
.Lnat_quiet:   .byte 2,34,15,4,16,0
.Lnat_bashful: .byte 28,6,33,5,8,34,14,0
.Lnat_rash:    .byte 9,6,33,5,0
.Lnat_calm:    .byte 13,6,14,10,0
.Lnat_gentle:  .byte 12,4,11,16,14,4,0
.Lnat_sassy:   .byte 33,6,33,33,32,0
.Lnat_careful: .byte 13,6,9,4,8,34,14,0
.Lnat_quirky:  .byte 2,34,15,9,3,32,0
    .align 2
.Lnature_table:
    .word .Lnat_hardy,.Lnat_lonely,.Lnat_brave,.Lnat_adamant,.Lnat_naughty
    .word .Lnat_bold,.Lnat_docile,.Lnat_relaxed,.Lnat_impish,.Lnat_lax
    .word .Lnat_timid,.Lnat_hasty,.Lnat_serious,.Lnat_jolly,.Lnat_naive
    .word .Lnat_modest,.Lnat_mild,.Lnat_quiet,.Lnat_bashful,.Lnat_rash
    .word .Lnat_calm,.Lnat_gentle,.Lnat_sassy,.Lnat_careful,.Lnat_quirky
    .align 2
.Lsuborders:
    .byte 0,1,2,3, 0,1,3,2, 0,2,1,3, 0,2,3,1, 0,3,1,2, 0,3,2,1
    .byte 1,0,2,3, 1,0,3,2, 2,0,1,3, 2,0,3,1, 3,0,1,2, 3,0,2,1
    .byte 1,2,0,3, 1,3,0,2, 2,1,0,3, 3,1,0,2, 2,3,0,1, 3,2,0,1
    .byte 1,2,3,0, 1,3,2,0, 2,1,3,0, 3,1,2,0, 2,3,1,0, 3,2,1,0
    .align 2
.Ldemo_pairs:
    .word 0x00007FFF,0x00000000, 0x000003FF,0x00000010
    .word 0x00007C1F,0x00000100, 0x00007FE0,0x00002000
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
.Lcolours:          .hword 0x0000, 0x000C, 0x0180, 0x3000, 0x001F, 0x03E0, 0x7C00, 0x4210
    .ltorg
