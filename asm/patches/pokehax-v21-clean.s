@ PHAX21 v21-clean: skip the retired POKEHAX LAB drawing body while retaining
@ its BG0 setup, then enter the existing PHAX21 renderer chain.
@
@ CLI_RUN_BUFFER_SCRIPT ABI (ARM state):
@   r0 = &client->param
@
@ Persistent layout at *(u32 *)0x03004230 + 4:
@   +0x00  "PHAX"
@   +0x04  body size in bytes
@   +0x08  additive u32 checksum of the complete body
@   +0x0C  version
@   +0x10  body
@
@ This patch accepts exactly the reconstructed PHAX21 body.  It validates the
@ complete old checksum and the affected word before writing.  It then checks
@ the complete new checksum and rolls the word back on any mismatch.  The
@ header checksum is the final persistent write.

    .arm
    .text
    .global _start
_start:
    push    {r4-r8, lr}
    mov     r7, r0                  @ &client->param
    mov     r0, #0
    str     r0, [r7]                @ failure answer unless commit succeeds

    ldr     r4, =0x03004230         @ BPGD gPokemonStoragePtr
    ldr     r4, [r4]
    ldr     r0, =0x02000000
    cmp     r4, r0
    blo     .Ldone
    ldr     r0, =0x0203E408         @ body end remains inside EWRAM
    cmp     r4, r0
    bhi     .Ldone
    tst     r4, #3
    bne     .Ldone
    add     r4, r4, #4              @ PHAX header

    ldr     r0, [r4, #0]
    ldr     r1, =0x58414850         @ "PHAX"
    cmp     r0, r1
    bne     .Ldone
    ldr     r0, [r4, #4]
    ldr     r1, =0x00001BE4         @ 7140 bytes
    cmp     r0, r1
    bne     .Ldone
    ldr     r0, [r4, #8]
    ldr     r1, =0x71D56E82
    cmp     r0, r1
    bne     .Ldone
    ldr     r0, [r4, #12]
    cmp     r0, #21
    bne     .Ldone

    add     r5, r4, #16             @ body
    add     r6, r5, #0x300
    add     r6, r6, #0xE4           @ body + 0x03E4
    ldr     r0, [r6]
    ldr     r1, =0x6C376BF5         @ ldr r5,[r6,#60]; ldr r7,[r6,#64]
    cmp     r0, r1
    bne     .Ldone

    mov     r8, r5
    ldr     r2, =0x00001BE4
    add     r2, r2, r5              @ body end
    mov     r3, #0
.Lsum_old:
    ldr     r0, [r8], #4
    add     r3, r3, r0
    cmp     r8, r2
    blo     .Lsum_old
    ldr     r0, =0x71D56E82
    cmp     r3, r0
    bne     .Ldone

    ldr     r0, =0xFEA6F000         @ bl 0x0201D934 from body + 0x03E4
    str     r0, [r6]

    mov     r8, r5
    mov     r3, #0
.Lsum_new:
    ldr     r0, [r8], #4
    add     r3, r3, r0
    cmp     r8, r2
    blo     .Lsum_new
    ldr     r0, =0x0444F28D
    cmp     r3, r0
    beq     .Lcommit

    ldr     r0, =0x6C376BF5         @ restore before reporting failure
    str     r0, [r6]
    b       .Ldone

.Lcommit:
    str     r3, [r4, #8]            @ final persistent write
    ldr     r0, =0x31434850         @ unique success u32: "PHC1"
    str     r0, [r7]

.Ldone:
    mov     r0, #1                  @ finish CLI_RUN_BUFFER_SCRIPT
    pop     {r4-r8, lr}
    bx      lr
