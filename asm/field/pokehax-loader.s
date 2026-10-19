@ Persistent POKEHAX LAB field loader. A saved RAM script stages this code when the player talks
@ to Mom. It reads the explicitly reserved Trainer Tower flash sector 30, validates the header and
@ additive body checksum, copies the fixed-address LAB body to EWRAM, then installs a tiny VBlank
@ wrapper. No unknown save pointers or callbacks are followed.

    .thumb
    .text
    .global _start
_start:
    push    {r4-r7, lr}

    @ Select flash bank 1 exactly as SwitchFlashBank does.
    ldr     r4, =0x0E005555
    mov     r0, #0xAA
    strb    r0, [r4]
    ldr     r5, =0x0E002AAA
    mov     r0, #0x55
    strb    r0, [r5]
    mov     r0, #0xB0
    strb    r0, [r4]
    ldr     r5, =0x0E000000
    mov     r0, #1
    strb    r0, [r5]

    ldr     r4, =0x0E00E000         @ physical sector 30 in bank 1
    ldr     r0, [r4]
    ldr     r1, =0x314C4850         @ "PHL1"
    cmp     r0, r1
    bne     .Ldone
    ldr     r7, [r4, #4]            @ body size, whole words
    mov     r0, #0xF0
    lsl     r0, r0, #4
    cmp     r7, r0
    bhi     .Ldone
    mov     r0, #3
    tst     r7, r0
    bne     .Ldone
    ldr     r6, [r4, #8]            @ expected additive checksum
    add     r4, #16
    ldr     r5, =0x0201C800
    mov     r0, #0                  @ byte index
.Lcopy_body:
    cmp     r0, r7
    bhs     .Lbody_copied
    ldrb    r3, [r4, r0]
    strb    r3, [r5, r0]
    add     r0, #1
    b       .Lcopy_body
.Lbody_copied:
    mov     r0, #0
    mov     r2, #0
.Lsum_body:
    cmp     r0, r7
    bhs     .Lbody_summed
    ldr     r1, [r5, r0]
    add     r2, r1
    add     r0, #4
    b       .Lsum_body
.Lbody_summed:
    cmp     r2, r6
    bne     .Ldone

    @ Copy and install the wrapper atomically with respect to VBlank.
    ldr     r4, =0x04000208
    ldrh    r7, [r4]
    mov     r0, #0
    strh    r0, [r4]
    ldr     r5, .Ltable
    ldr     r6, [r5]
    cmp     r6, #0
    beq     .Lime
    ldr     r0, =0x0203FC00
    adr     r1, .Lhook
    mov     r2, #(.Lhook_end - .Lhook) / 4
.Lcopy_hook:
    ldr     r3, [r1]
    str     r3, [r0]
    add     r0, #4
    add     r1, #4
    sub     r2, #1
    bne     .Lcopy_hook
    ldr     r0, =0x0203FC00
    mov     r1, #(.Lhook_original - .Lhook)
    add     r0, r1
    str     r6, [r0]
    ldr     r0, =0x0203FC00
    mov     r1, #(.Lhook_param - .Lhook)
    add     r0, r1
    mov     r1, #0
    str     r1, [r0]
    ldr     r0, =0x0203FC01
    str     r0, [r5]
.Lime:
    strh    r7, [r4]
.Ldone:
    pop     {r4-r7}
    pop     {r0}
    bx      r0

    .align 2
.Ltable: .word 0x03002730            @ patched per cartridge: &gIntrTable[4]

    .align 2
.Lhook:
    push    {r4-r7, lr}
    @ Let the game finish its normal OAM/BG/palette transfers first. Drawing the LAB afterwards
    @ makes its private BG0 state the final state for this VBlank instead of racing the engine.
    ldr     r3, .Lhook_original
    bl      .Lhook_call_r3
    ldr     r0, .Lhook_param_ptr
    ldr     r3, .Lhook_body
    mov     r2, pc
    add     r2, #5                  @ .Lhook_return | 1
    mov     lr, r2
    bx      r3                       @ ARM body; it returns to Thumb .Lhook_return
.Lhook_return:
    cmp     r0, #1
    bne     .Lhook_chain
    ldr     r1, .Lhook_table
    ldr     r2, .Lhook_original
    str     r2, [r1]                 @ EXIT removes this wrapper
.Lhook_chain:
    pop     {r4-r7}
    pop     {r0}
    bx      r0                       @ return to IntrMain; the original handler already ran
.Lhook_call_r3:
    bx      r3
    .align 2
.Lhook_param_ptr: .word 0x0203FC00 + .Lhook_param - .Lhook
.Lhook_body:      .word 0x0201C800
.Lhook_table:     .word 0x03002730
.Lhook_original:  .word 0
.Lhook_param:     .word 0
.Lhook_end:
    .ltorg
