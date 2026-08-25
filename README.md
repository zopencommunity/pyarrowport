[![Automatic version updates](https://github.com/zopencommunity/pyarrowport/actions/workflows/bump.yml/badge.svg)](https://github.com/zopencommunity/pyarrowport/actions/workflows/bump.yml)

# pyarrow for z/OS

Python bindings for Apache Arrow.

## Relationship to arrowport

Two ports, not one. pyarrow's `CMakeLists.txt` does `find_package(Arrow
REQUIRED)`, so the C++ library must already be installed. Arrow C++ is also
interpreter-independent while pyarrow builds a wheel per ABI, so combining them
would rebuild a very large C++ project once per interpreter to no purpose.

The source is the Arrow repository and the build runs in its `python/`
subdirectory via `ZOPEN_SRC_DIR`. The full tree has to be present:
`python/cmake_modules` is a symlink to `../cpp/cmake_modules`, and
`python/LICENSE.txt` and `python/NOTICE.txt` point at the repository root.
Building from a copied `python/` directory leaves those dangling and fails
first on license metadata and then on `include(BuildUtils)`.

## Why Arrow C++ must be shared

arrowport builds Arrow as DLLs, and that is a requirement of this port rather
than a preference.

When Arrow is static, upstream links it `PRIVATE` into `libarrow_python`
(`python/CMakeLists.txt` ~526), relying on ELF re-exporting those symbols to the
Cython extension modules. z/OS exports only what is explicitly exported, through
a side deck, so a static Arrow leaves every Arrow symbol unresolved when the
extensions link — the first being `arrow::compute::Initialize`. Building Arrow
shared resolves this, and it is also the configuration pyarrow links `PUBLIC`
and upstream tests most.

## Arrow features this port requires

pyarrow always builds `lib`, `_compute`, `_csv`, `_feather`, `_fs` and `_json`,
which pins the Arrow feature set. arrowport enables all of them:

| module     | Arrow feature       |
|------------|---------------------|
| `_compute` | `ARROW_COMPUTE`     |
| `_csv`     | `ARROW_CSV`         |
| `_json`    | `ARROW_JSON`        |
| `_feather` | `ARROW_IPC`         |
| `_fs`      | `ARROW_FILESYSTEM`  |

`ARROW_JSON` is why [rapidjsonport](https://github.com/zopencommunity/rapidjsonport)
exists: `arrow_python`'s sources include `<arrow/json/from_string.h>`
unconditionally, so an Arrow without JSON fails this build outright.

## What is patched

`patches/pyarrow-25.0.1-zos.patch`, in two parts, both consequences of z/OS
binding DLLs eagerly rather than resolving symbols at load time.

**1. Link the Python library.** `FindPython3Alt.cmake` asks for
`Development.Module`, which deliberately does not link `libpython`: on ELF and
Mach-O an extension leaves the Python C API undefined for the interpreter to
supply at `dlopen` time. Here every symbol must resolve when the module links,
so without it the build ends in a long list of

```
IEW2456E SYMBOL PyGILState_Ensure UNRESOLVED. ...
```

The patch requests the full `Development` component on z/OS, which provides
`Python3::Python` and its side deck, and links it `PUBLIC` on `arrow_python` so
the extension modules inherit it.

**2. Install the DLL.** A `SHARED` target here produces both a DLL
(`libarrow_python.so`) and a side deck (`libarrow_python.x`). CMake's z/OS
support maps both the `ARCHIVE` and `LIBRARY`/`RUNTIME` destinations to the side
deck — the generated `cmake_install.cmake` contains two

```
file(INSTALL ... TYPE STATIC_LIBRARY ... "libarrow_python.x")
```

entries and none for the `.so`. The wheel then ships a side deck describing a
library it does not contain. Nothing fails until import:

```
CEE3501S The module libarrow_python.so was not found.
```

The patch installs `$<TARGET_FILE:arrow_python>` explicitly. `zopen_post_install`
checks the file is present, so a regression here fails the build rather than
the user's first import.

## Runtime: LIBPATH

z/OS resolves DLLs through `LIBPATH` only. There is no `$ORIGIN` and the linker
rejects `-rpath` outright (`IEW5003 -r is not a valid option`). pyarrow sets
`CMAKE_INSTALL_RPATH "$ORIGIN"` for precisely this purpose on other platforms
and it is inert here, so both the package directory and Arrow's `lib` are named
explicitly through the ports' `.env` files.

The practical consequence: **the wheel is not usable outside the zopen
environment.** It installs, and then fails at import unless `LIBPATH` covers the
package directory and Arrow's libraries. That is a property of the platform, not
of this port.

## What is verified

Import plus each enabled module, on the built package:

```
pyarrow 25.0.1
compute     OK    6
csv         OK    3
json        OK    2
feather     OK    3
filesystem  OK    3
ipc         OK    3
codecs      OK    gzip:43 zstd:29 lz4:41 snappy:72 bz2:ERR
```

`bz2:ERR` is expected and not a z/OS limitation: Arrow's bzip2 support is
stream-only on every platform, and `pa.compress()` is the one-shot API.

pyarrow's own test suite is not run yet — it needs pytest, hypothesis and
pandas, whose availability here is untested. `ZOPEN_CHECK` is honestly set to
`skip` rather than to a check that cannot run.

## Known limitation: file timestamps

arrowport patches `localfs.cc` for z/OS, whose `struct stat` has `st_mtime`
(a `time_t`) and no `st_mtim` and no sub-second field. `pyarrow.fs` therefore
reports mtimes with **one-second resolution** where other platforms give
nanoseconds. Code comparing mtimes for equality or ordering within the same
second will behave differently here.
