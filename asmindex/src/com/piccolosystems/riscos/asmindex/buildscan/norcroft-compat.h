/*
 * norcroft-compat.h
 *
 * Force-included (-include) when a GCC-compatible front end, such as Eclipse
 * CDT's indexer or clangd, parses code written for the Norcroft C compiler.
 * It hides Norcroft-only syntax and supplies the macros Norcroft predefines,
 * so that the code parses as Norcroft would see it. It does not affect
 * compilation, which still uses Norcroft.
 */
#ifndef NORCROFT_COMPAT_H
#define NORCROFT_COMPAT_H

/* --- Macros Norcroft predefines ----------------------------------------- */
#define __CC_NORCROFT 1
#define __riscos 1
#define __acorn 1
#define __arm 1
#define __CHAR_UNSIGNED__ 1

/* Stop host compiler macros from selecting non-Norcroft code paths. */
#undef __GNUC__
#undef __GNUC_MINOR__
#undef __GNUC_PATCHLEVEL__
#undef __clang__
#undef __x86_64__
#undef __x86_64
#undef __linux__
#undef __linux
#undef __unix__
#undef __unix
#undef unix
#undef linux

/* --- Norcroft keywords --------------------------------------------------- */
#define __swi(n)
#define __value_in_regs
#define __pure
#define __irq
#define __packed
#define __global_reg(n)
#define __global_freg(n)
#define __caller_narrow
#define __weak
#define __int64 long long

/* Internal keywords used by Norcroft's own CLib headers (stdarg.h, stddef.h,
 * complex.h, tgmath.h). */
#define ___type
#define ___assert(cond, msg) ((void) 0)
#define ___typeof(x) 0
#define ___i (1.0fi)
#define ___select(dummy, f, d, l, ...) d

/* Norcroft intrinsics */
#define __schedule_barrier() ((void) 0)
#define __current_sp() 0u
#define __current_pc() 0u
#define __return_address() 0u
/* Unary operators, used like sizeof: "__abs (int) (j)". */
#define __abs

#endif
