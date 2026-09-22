#!/usr/bin/env python3
"""
post_production/generate_pixel_log_luts.py

Wrapper script delegating directly to tools/make_luts.py, ensuring that all
post-production deliverables and 3D LUTs remain strictly synchronized with
log_params.json and the rectified Michaelis-Menten color science.
"""

import os
import sys
import subprocess

def main():
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    make_luts_script = os.path.join(repo_root, "tools", "make_luts.py")
    if not os.path.exists(make_luts_script):
        print(f"Error: Could not locate {make_luts_script}", file=sys.stderr)
        sys.exit(1)

    result = subprocess.run([sys.executable, make_luts_script], cwd=repo_root)
    sys.exit(result.returncode)

if __name__ == "__main__":
    main()
