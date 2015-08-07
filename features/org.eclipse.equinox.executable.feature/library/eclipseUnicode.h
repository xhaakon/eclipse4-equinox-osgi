/*******************************************************************************
 * Copyright (c) 2000, 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at 
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Silenio Quarti
 *******************************************************************************/

#ifndef ECLIPSE_UNICODE_H
#define ECLIPSE_UNICODE_H

#ifdef _WIN32

#ifdef UNICODE
#define _UNICODE
#endif
#include <windows.h>
#include <tchar.h>
#include <ctype.h>

#ifdef __MINGW32__
# ifdef UNICODE
#  ifndef _TCHAR
#   define _TCHAR TCHAR
#  endif /* _TCHAR */
#  ifndef _tgetcwd
#   define _tgetcwd _wgetcwd
#  endif /* _tgetcwd */
#  ifndef _tstat
#   define _tstat _wstat
#  endif /* _tstat */
#  ifndef _topendir
#   define _topendir _wopendir
#  endif /* _topendir */
#  ifndef _treaddir
#   define _treaddir _wreaddir
#  endif /* _treaddir */
#  ifndef _tclosedir
#   define _tclosedir _wclosedir
#  endif /* _tclosedir */
#  ifndef _tDIR
#   define _tDIR _WDIR
#  endif /* _tDIR */
# else /* UNICODE */
#  ifndef _TCHAR
#   define _TCHAR char
#  endif /* _TCHAR */
#  ifndef _tgetcwd
#   define _tgetcwd getcwd
#  endif /* _tgetcwd */
#  ifndef _tstat
#   define _tstat _stat
#  endif /* _tstat */
#  ifndef _topendir
#error message!
#   define _topendir opendir
#  endif /* _topendir */
#  ifndef _treaddir
#   define _treaddir readdir
#  endif /* _treaddir */
#  ifndef _tclosedir
#   define _tclosedir closedir
#  endif /* _tclosedir */
#  ifndef _tDIR
#   define _tDIR DIR
#  endif /* _tDIR */
# endif /* UNICODE */
#endif /* __MINGW32__ */

#define _T_ECLIPSE _T

#else /* Platforms other than Windows */

#define _TCHAR char
#define _T_ECLIPSE(s) s
#define _fgetts fgets
#define _stat stat
#define _stprintf sprintf
#define _ftprintf fprintf
#define _stscanf sscanf
#define _tcscat strcat
#define _tcschr strchr
#define _tcspbrk strpbrk
#define _tcscmp strcmp
#define _tcscpy strcpy
#define _tcsdup strdup
#define _tcsicmp strcasecmp
#define _tcslen strlen
#define _tcsncpy strncpy
#define _tcsrchr strrchr
#define _tfopen fopen
#define _tgetcwd getcwd
#define _tgetenv getenv
#define _tcstol strtol
#define _tcstok strtok
#ifndef LINUX
#define _totupper toupper
#endif /* LINUX */
#define _tprintf printf
#define _tstat stat
#define _tcsncmp strncmp
#define _tcsstr strstr 
#define _topendir opendir
#define _treaddir readdir
#define _tclosedir closedir
#define _tDIR DIR
#endif /* _WIN32 */

#endif /* ECLIPSE_UNICODE_H */
