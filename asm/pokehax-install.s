@ One-time POKEHAX LAB installer. The multi-stage Mystery Gift transfer has already copied the LAB
@ body to 0x0201C800. This payload reserves raw flash sector 30 (Trainer Tower data), writes a
@ guarded body image there, and binds a validated field loader to Mom in the live SaveBlock1.

    .arm
    .text
    .global _start
_start:
    push    {r4-r8, lr}
    mov     r8, r0                  @ &client->param
    ldr     r4, =0x0201D800         @ one complete 4 KiB flash-sector image
    mov     r0, #0
    mov     r1, #0x400
.Lzero_sector:
    str     r0, [r4], #4
    subs    r1, r1, #1
    bne     .Lzero_sector
    ldr     r4, =0x0201D800
    ldr     r0, =0x314C4850         @ "PHL1"
    str     r0, [r4]
    ldr     r6, .Lbody_size
    str     r6, [r4, #4]
    ldr     r5, =0x0201C800
    add     r4, r4, #16
    mov     r7, #0
.Lcopy_body:
    ldr     r0, [r5], #4
    str     r0, [r4], #4
    add     r7, r7, r0
    subs    r6, r6, #4
    bne     .Lcopy_body
    ldr     r4, =0x0201D800
    str     r7, [r4, #8]

    mov     r0, #30                @ sector 30: documented Trainer Tower reservation
    mov     r1, r4
    adr     r3, .Lthunk
    add     r3, r3, #1
    mov     lr, pc
    bx      r3

    @ Replace only SaveBlock1.ramScript with a fully checksummed, Mom-bound script.
    ldr     r4, .Lsb1ptr
    ldr     r4, [r4]
    ldr     r0, =0x0000361C
    add     r4, r4, r0
    mov     r0, #0
    mov     r1, #251               @ checksum + 1000-byte RamScriptData
.Lzero_script:
    str     r0, [r4], #4
    subs    r1, r1, #1
    bne     .Lzero_script
    sub     r4, r4, #1004
    ldr     r0, .Lscript_crc
    str     r0, [r4]
    ldr     r0, =0x01000433         @ magic 51, map 4/0, Mom object 1
    str     r0, [r4, #4]
    add     r4, r4, #8
    adr     r5, .Lscript
    ldr     r6, .Lscript_size
.Lcopy_script:
    ldrb    r0, [r5], #1
    strb    r0, [r4], #1
    subs    r6, r6, #1
    bne     .Lcopy_script

    ldr     r0, =0x50484C31         @ report successful install
    str     r0, [r8]
    mov     r0, #1
    pop     {r4-r8, lr}
    bx      lr

    .align 2
.Lbody_size:  .word 0xBBBBBBBB
.Lsb1ptr:     .word 0xAAAAAAAA
.Lscript_crc: .word 0xCCCCCCCC
.Lscript_size:.word 0xDDDDDDDD
.Lthunk:      .hword 0xDF48, 0x4770  @ swi 0x48 ; bx lr
.Lscript:
    .space 720, 0xEE
