#!/usr/bin/env python3
#
# jSoapySDR - builds the bundled SoapySDR native image for one platform.
#
# SPDX-License-Identifier: Apache-2.0
"""
Builds SoapySDR and the bundled driver modules for the current (or specified) platform and writes the result to
<repo>/natives/<platform>/ together with a manifest that the Java loader uses to extract and load it.

    python3 native/build.py                       # host platform
    python3 native/build.py --platform macos-aarch64

Steps:
  1. SoapySDR (all platforms) and a static libusb (Linux, macOS; autotools build) are built from pinned git sources
     into build/<platform>/deps. On Windows, vcpkg provides static libusb and pthreads-win32 (see vcpkg.json and
     triplets/).
  2. libbladeRF is built as a shared library with its own CMake project against that libusb.
  3. CMakeLists.txt builds the driver modules and installs the bundle; it is then copied to natives/<platform> with a
     manifest (bundle.txt) listing every file with its SHA-256.

Requirements: git, CMake >= 3.24, a C/C++ toolchain (Visual Studio 2022+ on Windows), Ninja (optional, used when
present on Linux/macOS), and on Linux/macOS autoconf, automake, libtool and make for libusb.
"""

import argparse
import hashlib
import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path

NATIVE_DIR = Path(__file__).resolve().parent
REPO_DIR = NATIVE_DIR.parent

# Must match "builtin-baseline" in vcpkg.json (Windows only)
VCPKG_COMMIT = "4334d8b4c8916018600212ab4dd4bbdc343065d1"

# name: (repository, tag, commit the tag must resolve to)
SOURCES = {
    "SoapySDR": ("https://github.com/pothosware/SoapySDR", "soapy-sdr-0.8.1", "1cf5a539a21414ff509ff7d0eedfc5fa8edb90c6"),
    "libusb": ("https://github.com/libusb/libusb", "v1.0.29", "15a7ebb4d426c5ce196684347d2b7cafad862626"),
    "bladeRF": ("https://github.com/Nuand/bladeRF", "libbladeRF_v2.6.0", "09c82087786702d2812f3f0fc909beccf3277233"),
}

# Git submodules needed from a source: name: [(path, commit)]
SUBMODULES = {
    # Analog Devices AD9361 driver used by libbladeRF for the bladeRF 2.0 micro
    "bladeRF": [("thirdparty/analogdevicesinc/no-OS", "0bba46e6f6f75785a65d425ece37d0a04daf6157")],
}

PLATFORMS = ["linux-x86_64", "linux-aarch64", "macos-x86_64", "macos-aarch64", "windows-x86_64", "windows-aarch64"]

MACOS_DEPLOYMENT_TARGET = "11.0"

# MSVC runtime DLLs that are loaded explicitly (in this order) before SoapySDR.dll. vcruntime140_1.dll exists only for
# x64; the copy in the ARM64 redistributable folder serves x64 emulation and cannot load into an ARM64 process.
WINDOWS_PRELOAD = {
    "windows-x86_64": ["vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll", "bladeRF.dll"],
    "windows-aarch64": ["vcruntime140.dll", "msvcp140.dll", "bladeRF.dll"],
}

# Driver modules always built; SDRplay adds one unless --sdrplay-include "" is given
EXPECTED_MODULES = 6


def detect_platform():
    system = platform.system()
    os_name = {"Linux": "linux", "Darwin": "macos", "Windows": "windows"}.get(system)
    if os_name is None:
        sys.exit("Unsupported operating system: " + system)

    machine = platform.machine().lower()
    if os_name == "windows":
        # An emulated x64 Python on Windows on Arm reports AMD64; ask the OS instead
        machine = (os.environ.get("PROCESSOR_ARCHITEW6432") or os.environ.get("PROCESSOR_ARCHITECTURE") or machine).lower()
    arch = {"x86_64": "x86_64", "amd64": "x86_64", "aarch64": "aarch64", "arm64": "aarch64"}.get(machine)
    if arch is None:
        sys.exit("Unsupported architecture: " + machine)
    return os_name + "-" + arch


def run(cmd, **kwargs):
    print("+ " + " ".join(str(c) for c in cmd), flush=True)
    subprocess.run([str(c) for c in cmd], check=True, **kwargs)


def cmake_path(path):
    """Paths passed to CMake use forward slashes: backslashes in Windows paths become escape sequences in the
    install scripts CMake generates."""
    return Path(path).as_posix()


def fetch(name, src_root):
    repo, tag, commit = SOURCES[name]
    path = Path(src_root) / name
    if not path.exists():
        run(["git", "clone", "-q", "--depth", "1", "--branch", tag, repo, path])
    actual = subprocess.run(["git", "-C", str(path), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
    if actual != commit:
        sys.exit("%s %s resolved to %s, expected %s" % (name, tag, actual, commit))
    for sub_path, sub_commit in SUBMODULES.get(name, []):
        sub_dir = path / sub_path
        current = subprocess.run(["git", "-C", str(sub_dir), "rev-parse", "HEAD"], capture_output=True,
                                 text=True).stdout.strip() if (sub_dir / ".git").exists() else ""
        if current != sub_commit:
            run(["git", "-C", path, "submodule", "update", "--init", "--depth", "1", "--", sub_path])
            current = subprocess.run(["git", "-C", str(sub_dir), "rev-parse", "HEAD"], capture_output=True,
                                     text=True).stdout.strip()
        if current != sub_commit:
            sys.exit("%s submodule %s is at %s, expected %s" % (name, sub_path, current, sub_commit))
    return path


def is_windows(plat):
    return plat.startswith("windows")


def cmake_platform_args(plat):
    """Generator and architecture arguments shared by every CMake project in the build."""
    if is_windows(plat):
        # The Visual Studio 2022 (v143) toolset: the MSVC runtime that the JVM process may already have loaded is
        # what our DLLs bind to, and code built with a newer toolset can require exports an older runtime lacks.
        toolset = os.environ.get("JSOAPYSDR_MSVC_TOOLSET", "v143")
        return ["-A", "ARM64" if plat.endswith("aarch64") else "x64"] + (["-T", toolset] if toolset else [])
    args = ["-G", "Ninja"] if shutil.which("ninja") else []
    if plat.startswith("macos"):
        args += ["-DCMAKE_OSX_ARCHITECTURES=" + osx_arch(plat), "-DCMAKE_OSX_DEPLOYMENT_TARGET=" + MACOS_DEPLOYMENT_TARGET]
    return args


def osx_arch(plat):
    return "arm64" if plat.endswith("aarch64") else "x86_64"


def build_soapysdr(plat, src, build_dir, deps):
    args = ["cmake", "-S", src, "-B", build_dir / "soapysdr", "-DCMAKE_BUILD_TYPE=Release",
            "-DCMAKE_INSTALL_PREFIX=" + cmake_path(deps), "-DCMAKE_INSTALL_LIBDIR=lib",
            "-DENABLE_APPS=OFF", "-DENABLE_DOCS=OFF", "-DENABLE_TESTS=OFF",
            "-DENABLE_PYTHON=OFF", "-DENABLE_PYTHON3=OFF", "-DENABLE_LUAJIT=OFF",
            # SoapySDR 0.8.1 declares cmake_minimum_required(3.1), which CMake 4 rejects without this
            "-DCMAKE_POLICY_VERSION_MINIMUM=3.5"] + cmake_platform_args(plat)
    if is_windows(plat):
        # Compatible with an older msvcp140.dll that may already be loaded in the JVM process
        args.append("-DCMAKE_CXX_FLAGS_INIT=/D_DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR")
    run(args)
    run(["cmake", "--build", build_dir / "soapysdr", "--config", "Release", "--parallel"])
    run(["cmake", "--install", build_dir / "soapysdr", "--config", "Release"])


def build_libusb(plat, src, build_dir, deps):
    """Static, position independent libusb without udev (Linux) using the autotools build."""
    if not (src / "configure").exists():
        env = dict(os.environ)
        if not shutil.which("libtoolize") and shutil.which("glibtoolize"):
            env["LIBTOOLIZE"] = "glibtoolize"  # Homebrew installs GNU libtool with a "g" prefix
        run(["autoreconf", "-fi"], cwd=src, env=env)
    work = build_dir / "libusb"
    if work.exists():
        shutil.rmtree(work)
    work.mkdir(parents=True)
    env = dict(os.environ)
    cflags = "-O2 -fPIC"
    if plat.startswith("macos"):
        cflags += " -arch %s -mmacosx-version-min=%s" % (osx_arch(plat), MACOS_DEPLOYMENT_TARGET)
        env["LDFLAGS"] = "-arch %s -mmacosx-version-min=%s" % (osx_arch(plat), MACOS_DEPLOYMENT_TARGET)
    env["CFLAGS"] = cflags
    configure = [src / "configure", "--prefix=" + str(deps), "--libdir=" + str(deps / "lib"),
                 "--enable-static", "--disable-shared", "--with-pic", "--disable-examples-build",
                 "--disable-tests-build"]
    if plat.startswith("linux"):
        configure.append("--disable-udev")
    run(configure, cwd=work, env=env)
    run(["make", "-j%d" % (os.cpu_count() or 2)], cwd=work, env=env)
    run(["make", "install"], cwd=work, env=env)


def vcpkg_install(plat, vcpkg, build_dir):
    """Windows: installs libusb and pthreads4w (vcpkg.json) for the platform's triplet, returning the triplet's
    installed directory. Done up front because libbladeRF needs libusb before the main project is configured."""
    triplet = ("arm64" if plat.endswith("aarch64") else "x64") + "-windows-jsoapy"
    installed = build_dir / "vcpkg_installed"
    run([vcpkg / "vcpkg.exe", "install", "--triplet", triplet,
         "--overlay-triplets=" + str(NATIVE_DIR / "triplets"),
         "--x-manifest-root=" + str(NATIVE_DIR),
         "--x-install-root=" + str(installed),
         "--disable-metrics"])
    return triplet, installed


def build_bladerf(plat, src, build_dir, deps, libusb_include, libusb_library):
    """libbladeRF as a shared library (its only supported form) built by its own CMake project against our static
    libusb, then copied with its headers into the dependency prefix. Returns (link library, runtime file)."""
    # Configure the library only: the utilities need extra libraries (curses, libedit) and another submodule
    utilities = src / "host" / "utilities" / "CMakeLists.txt"
    utilities.write_text("# jSoapySDR: utilities are not built\n")

    work = build_dir / "bladerf"
    args = ["cmake", "-S", src / "host", "-B", work, "-DCMAKE_BUILD_TYPE=Release",
            "-DCMAKE_POLICY_VERSION_MINIMUM=3.5",
            "-DBUILD_DOCUMENTATION=OFF", "-DTEST_LIBBLADERF=OFF",
            # Newer compilers warn about libbladeRF code that its warnings-as-errors default turns into failures
            "-DTREAT_WARNINGS_AS_ERRORS=OFF",
            "-DENABLE_BACKEND_LIBUSB=ON", "-DENABLE_BACKEND_CYAPI=OFF", "-DENABLE_BACKEND_DUMMY=OFF",
            "-DENABLE_LIBBLADERF_SYSLOG=OFF",
            # Use our static libusb rather than searching for one
            "-DLIBUSB_FOUND=TRUE", "-DLIBUSB_SKIP_VERSION_CHECK=ON",
            "-DLIBUSB_HEADER_FILE=" + cmake_path(Path(libusb_include) / "libusb.h"),
            "-Dusb_LIBRARY=" + cmake_path(libusb_library)] + cmake_platform_args(plat)
    if plat.startswith("macos"):
        args.append("-DCMAKE_SHARED_LINKER_FLAGS_INIT=-framework CoreFoundation -framework IOKit -framework Security -lobjc")
    elif plat.startswith("linux"):
        args.append("-DCMAKE_SHARED_LINKER_FLAGS_INIT=-pthread")
    else:
        args.append("-DCMAKE_SHARED_LINKER_FLAGS_INIT=advapi32.lib setupapi.lib")
    run(args)
    run(["cmake", "--build", work, "--config", "Release", "--target", "libbladerf_shared", "--parallel"])

    include = deps / "include"
    include.mkdir(parents=True, exist_ok=True)
    for header in (src / "host" / "libraries" / "libbladeRF" / "include").glob("*.h"):
        shutil.copy2(header, include / header.name)

    if is_windows(plat):
        dll = next(work.rglob("bladeRF.dll"))
        lib = next(p for p in work.rglob("bladeRF.lib"))
        (deps / "bin").mkdir(parents=True, exist_ok=True)
        (deps / "lib").mkdir(parents=True, exist_ok=True)
        shutil.copy2(dll, deps / "bin" / dll.name)
        shutil.copy2(lib, deps / "lib" / lib.name)
        return deps / "lib" / lib.name, deps / "bin" / dll.name

    pattern = "libbladeRF.*.dylib" if plat.startswith("macos") else "libbladeRF.so.*"
    real = next(p for p in work.rglob(pattern) if p.is_file() and not p.is_symlink())
    (deps / "lib").mkdir(parents=True, exist_ok=True)
    target = deps / "lib" / real.name
    shutil.copy2(real, target)
    return target, target


def ensure_vcpkg(root):
    root = Path(root)
    exe = root / ("vcpkg.exe" if os.name == "nt" else "vcpkg")
    head = root / ".git"
    if head.exists():
        current = subprocess.run(["git", "-C", str(root), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
        if current != VCPKG_COMMIT:
            run(["git", "-C", root, "fetch", "--depth", "1", "origin", VCPKG_COMMIT])
            run(["git", "-C", root, "checkout", "--force", VCPKG_COMMIT])
    else:
        root.mkdir(parents=True, exist_ok=True)
        run(["git", "-C", root, "init", "-q"])
        run(["git", "-C", root, "remote", "add", "origin", "https://github.com/microsoft/vcpkg"])
        run(["git", "-C", root, "fetch", "--depth", "1", "origin", VCPKG_COMMIT])
        run(["git", "-C", root, "checkout", "--force", VCPKG_COMMIT])
    if not exe.exists():
        if os.name == "nt":
            run([root / "bootstrap-vcpkg.bat", "-disableMetrics"])
        else:
            run(["sh", root / "bootstrap-vcpkg.sh", "-disableMetrics"])
    return root


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_bundle(stage, out, plat, expected_modules):
    if out.exists():
        shutil.rmtree(out)
    shutil.copytree(stage, out)

    files = sorted(p for p in out.rglob("*") if p.is_file() and p.parts[len(out.parts)] != "licenses")
    rel = [p.relative_to(out).as_posix() for p in files]

    if plat.startswith("windows"):
        core = "bin/SoapySDR.dll"
        preload = ["bin/" + name for name in WINDOWS_PRELOAD[plat] if ("bin/" + name) in rel]
    elif plat.startswith("macos"):
        core = next(r for r in rel if r.startswith("lib/libSoapySDR.") and r.endswith(".dylib"))
        preload = []
    else:
        core = next(r for r in rel if r.startswith("lib/libSoapySDR.so"))
        preload = []

    modules = [r for r in rel if r.startswith("lib/SoapySDR/modules")]
    if len(modules) != expected_modules:
        sys.exit("Expected %d driver modules but found: %s" % (expected_modules, modules))

    lines = ["# jSoapySDR native bundle", "platform=" + plat, "core=" + core]
    lines += ["preload=" + p for p in preload]
    for path, name in zip(files, rel):
        lines.append("file=%s:%d:%s" % (sha256(path), path.stat().st_size, name))
    (out / "bundle.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")

    print("\nWrote %s" % out)
    for line in lines[1:]:
        print("  " + line)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--platform", choices=PLATFORMS, default=None)
    parser.add_argument("--vcpkg-root", default=str(NATIVE_DIR / "build" / "vcpkg"), help="Windows only")
    parser.add_argument("--out", default=None, help="output directory (default <repo>/natives/<platform>)")
    parser.add_argument("--sdrplay-include",
                        default=os.environ.get("JSOAPYSDR_SDRPLAY_INCLUDE", str(NATIVE_DIR / "sdrplay" / "include")),
                        help="directory containing the SDRplay API 3.x headers (default: the copies in "
                             "native/sdrplay/include); pass an empty string to skip the SDRplay module")
    args = parser.parse_args()

    plat = args.platform or detect_platform()
    build_dir = NATIVE_DIR / "build" / plat
    deps = build_dir / "deps"
    stage = build_dir / "stage"
    src_root = NATIVE_DIR / "build" / "src"
    for path in (deps, stage):
        if path.exists():
            shutil.rmtree(path)

    soapysdr_src = fetch("SoapySDR", src_root)
    build_soapysdr(plat, soapysdr_src, build_dir, deps)

    configure = [
        "cmake", "-S", NATIVE_DIR, "-B", build_dir / "modules",
        "-DCMAKE_BUILD_TYPE=Release",
        "-DCMAKE_PREFIX_PATH=" + cmake_path(deps),
        "-DCMAKE_INSTALL_PREFIX=" + cmake_path(stage),
        "-DJS_LICENSE_SOAPYSDR=" + cmake_path(soapysdr_src / "LICENSE_1_0.txt"),
    ] + cmake_platform_args(plat)

    if is_windows(plat):
        vcpkg = ensure_vcpkg(args.vcpkg_root)
        triplet, installed = vcpkg_install(plat, vcpkg, build_dir)
        libusb_include = installed / triplet / "include" / "libusb-1.0"
        libusb_library = installed / triplet / "lib" / "libusb-1.0.lib"
        configure += [
            "-DCMAKE_TOOLCHAIN_FILE=" + cmake_path(vcpkg / "scripts" / "buildsystems" / "vcpkg.cmake"),
            "-DVCPKG_TARGET_TRIPLET=" + triplet,
            "-DVCPKG_OVERLAY_TRIPLETS=" + cmake_path(NATIVE_DIR / "triplets"),
            "-DVCPKG_INSTALLED_DIR=" + cmake_path(installed),
            "-DVCPKG_MANIFEST_INSTALL=OFF",
        ]
    else:
        libusb_src = fetch("libusb", src_root)
        build_libusb(plat, libusb_src, build_dir, deps)
        libusb_include = deps / "include" / "libusb-1.0"
        libusb_library = deps / "lib" / "libusb-1.0.a"
        configure.append("-DJS_LICENSE_LIBUSB=" + cmake_path(libusb_src / "COPYING"))

    bladerf_src = fetch("bladeRF", src_root)
    bladerf_link, bladerf_runtime = build_bladerf(plat, bladerf_src, build_dir, deps, libusb_include, libusb_library)
    configure += [
        "-DJS_BLADERF_SOURCE=" + cmake_path(bladerf_src),
        "-DJS_BLADERF_LIBRARY=" + cmake_path(bladerf_link),
        "-DJS_BLADERF_RUNTIME=" + cmake_path(bladerf_runtime),
        "-DJS_BLADERF_INCLUDE=" + cmake_path(deps / "include"),
    ]
    expected_modules = EXPECTED_MODULES
    if args.sdrplay_include:
        configure.append("-DJS_SDRPLAY_INCLUDE=" + cmake_path(Path(args.sdrplay_include).resolve()))
        expected_modules += 1

    run(configure)
    run(["cmake", "--build", build_dir / "modules", "--config", "Release", "--parallel"])
    run(["cmake", "--install", build_dir / "modules", "--config", "Release"])

    out = Path(args.out) if args.out else REPO_DIR / "natives" / plat
    write_bundle(stage, out, plat, expected_modules)


if __name__ == "__main__":
    main()
