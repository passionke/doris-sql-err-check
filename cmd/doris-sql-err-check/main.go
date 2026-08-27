// doris-sql-err-check portable single-file CLI.
// Embeds the fat jar; finds a JDK at runtime (same contract as bin/ launcher).
// Author: kejiqing
package main

import (
	_ "embed"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
)

//go:embed embed/app.jar
var appJar []byte

func main() {
	java, err := resolveJava()
	if err != nil {
		fmt.Fprintf(os.Stderr, "doris-sql-err-check: %v\n", err)
		os.Exit(127)
	}

	jarPath, err := materializeJar()
	if err != nil {
		fmt.Fprintf(os.Stderr, "doris-sql-err-check: %v\n", err)
		os.Exit(1)
	}

	args := append([]string{"-jar", jarPath}, os.Args[1:]...)
	cmd := exec.Command(java, args...)
	cmd.Stdin = os.Stdin
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Run(); err != nil {
		if ee, ok := err.(*exec.ExitError); ok {
			os.Exit(ee.ExitCode())
		}
		fmt.Fprintf(os.Stderr, "doris-sql-err-check: %v\n", err)
		os.Exit(1)
	}
}

func materializeJar() (string, error) {
	if override := os.Getenv("DORIS_SQL_ERR_CHECK_JAR"); override != "" {
		if _, err := os.Stat(override); err != nil {
			return "", fmt.Errorf("DORIS_SQL_ERR_CHECK_JAR not found: %s", override)
		}
		return override, nil
	}

	cacheDir, err := os.UserCacheDir()
	if err != nil {
		cacheDir = os.TempDir()
	}
	dir := filepath.Join(cacheDir, "doris-sql-err-check")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	// versioned by size so rebuilds refresh
	name := fmt.Sprintf("app-%d.jar", len(appJar))
	path := filepath.Join(dir, name)
	if st, err := os.Stat(path); err == nil && st.Size() == int64(len(appJar)) {
		return path, nil
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, appJar, 0o644); err != nil {
		return "", err
	}
	if err := os.Rename(tmp, path); err != nil {
		return "", err
	}
	return path, nil
}

func resolveJava() (string, error) {
	if home := os.Getenv("JAVA_HOME"); home != "" {
		p := filepath.Join(home, "bin", javaExe())
		if fileExists(p) {
			return p, nil
		}
	}
	// bundled JDK next to repo when developing (optional)
	if exe, err := os.Executable(); err == nil {
		root := filepath.Clean(filepath.Join(filepath.Dir(exe), ".."))
		candidates := []string{
			filepath.Join(root, ".tools", "jdk", "openjdk", "26.0.2.1", "libexec", "openjdk.jdk", "Contents", "Home", "bin", javaExe()),
		}
		for _, c := range candidates {
			if fileExists(c) {
				return c, nil
			}
		}
	}
	if p, err := exec.LookPath(javaExe()); err == nil {
		return p, nil
	}
	return "", fmt.Errorf("java not found (need JDK 17+; set JAVA_HOME). os=%s/%s", runtime.GOOS, runtime.GOARCH)
}

func javaExe() string {
	if runtime.GOOS == "windows" {
		return "java.exe"
	}
	return "java"
}

func fileExists(p string) bool {
	st, err := os.Stat(p)
	return err == nil && !st.IsDir()
}
