// Package archtest enforces the feature-based Clean Architecture rules on
// the import graph, so a wrong dependency fails `go test` instead of
// silently eroding the design.
//
// Layout: internal/feature/<feature>/<layer>, where the layers are
//
//	domain          entities, value objects, rules, repository interfaces
//	application     use cases and the ports they need
//	infrastructure  implementations of the ports (SQLite, disk, ffmpeg, ...)
//	presentation    HTTP delivery
//
// Dependencies point inwards: presentation/infrastructure -> application ->
// domain. Features talk to each other only through another feature's
// domain types (and the allow-listed feature dependencies below); the
// composition root (app) is the only place that sees every feature.
package archtest

import (
	"go/parser"
	"go/token"
	"io/fs"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

const module = "musicstream/internal/"

var layers = map[string]bool{"domain": true, "application": true, "infrastructure": true, "presentation": true}

// ownLayerDeps lists which layers of the same feature a layer may import.
var ownLayerDeps = map[string][]string{
	"domain":         nil,
	"application":    {"domain"},
	"infrastructure": {"domain", "application"},
	"presentation":   {"domain", "application"},
}

// featureDeps are the allowed feature -> feature dependencies. They are
// one-way; anything not listed (including any cycle) fails the test.
var featureDeps = map[string]bool{
	"playlist->library": true,
	"stream->library":   true,
	"ingest->library":   true,
}

// crossLayerExceptions are deliberate imports of another feature's
// non-domain layer.
var crossLayerExceptions = map[string]bool{
	// The track JSON contract is shared so playlists render
	// tracks exactly like the catalog does.
	"feature/playlist/presentation->feature/library/presentation": true,
}

// pkgImports maps "feature/library/presentation" -> internal imports (same form).
func pkgImports(t *testing.T) map[string][]string {
	t.Helper()
	out := map[string][]string{}
	root := ".."
	err := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") || strings.HasSuffix(path, "_test.go") {
			return err
		}
		f, err := parser.ParseFile(token.NewFileSet(), path, nil, parser.ImportsOnly)
		if err != nil {
			return err
		}
		rel, _ := filepath.Rel(root, filepath.Dir(path))
		pkg := filepath.ToSlash(rel)
		for _, imp := range f.Imports {
			p, _ := strconv.Unquote(imp.Path.Value)
			if strings.HasPrefix(p, module) {
				out[pkg] = append(out[pkg], strings.TrimPrefix(p, module))
			}
		}
		if _, ok := out[pkg]; !ok {
			out[pkg] = nil
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	return out
}

// featureLayer splits "feature/<name>/<layer>" ("" layer if not a layer package).
func featureLayer(p string) (feature, layer string, ok bool) {
	parts := strings.Split(p, "/")
	if len(parts) < 2 || parts[0] != "feature" {
		return "", "", false
	}
	if len(parts) == 3 && layers[parts[2]] {
		return parts[1], parts[2], true
	}
	return parts[1], "", true
}

func contains(xs []string, x string) bool {
	for _, v := range xs {
		if v == x {
			return true
		}
	}
	return false
}

func TestFeatureLayout(t *testing.T) {
	for pkg := range pkgImports(t) {
		if _, layer, ok := featureLayer(pkg); ok && layer == "" {
			t.Errorf("%s: feature code must live in one of domain/application/infrastructure/presentation", pkg)
		}
	}
}

func TestDependencyRules(t *testing.T) {
	for pkg, imports := range pkgImports(t) {
		for _, imp := range imports {
			if v := violation(pkg, imp); v != "" {
				t.Errorf("%s imports %s: %s", pkg, imp, v)
			}
		}
	}
}

func violation(pkg, imp string) string {
	isApp := pkg == "app" || strings.HasPrefix(pkg, "app/")
	switch {
	case strings.HasPrefix(imp, "app") || imp == "config":
		if !isApp {
			return "only the composition root may import app/config"
		}
		return ""
	case isApp:
		return ""
	case strings.HasPrefix(pkg, "shared"):
		if !strings.HasPrefix(imp, "shared") {
			return "shared kernel must not depend on features or platform"
		}
		return ""
	case strings.HasPrefix(pkg, "platform"):
		if !strings.HasPrefix(imp, "shared") && !strings.HasPrefix(imp, "platform") {
			return "platform must not depend on features"
		}
		return ""
	}

	feature, layer, ok := featureLayer(pkg)
	if !ok || layer == "" {
		return ""
	}
	if strings.HasPrefix(imp, "shared") {
		return ""
	}
	if strings.HasPrefix(imp, "platform") {
		if layer == "domain" || layer == "application" {
			return layer + " must not depend on technical infrastructure (define a port instead)"
		}
		return ""
	}
	impFeature, impLayer, ok := featureLayer(imp)
	if !ok {
		return ""
	}
	if impFeature == feature {
		if !contains(ownLayerDeps[layer], impLayer) {
			return layer + " must not depend on " + impLayer + " (dependencies point inwards)"
		}
		return ""
	}
	if !featureDeps[feature+"->"+impFeature] {
		return "unexpected feature dependency " + feature + " -> " + impFeature + " (update featureDeps deliberately)"
	}
	if impLayer != "domain" && !crossLayerExceptions[pkg+"->"+imp] {
		return "other features may only be used through their domain types; declare a port in your application layer"
	}
	return ""
}

// TestRulesRejectViolations guards the guard: each case must be rejected.
func TestRulesRejectViolations(t *testing.T) {
	for _, c := range [][2]string{
		{"feature/library/domain", "feature/library/application"},
		{"feature/library/application", "feature/library/infrastructure"},
		{"feature/library/application", "platform/sqlitex"},
		{"feature/library/presentation", "feature/library/infrastructure"},
		{"feature/playlist/application", "feature/library/application"},
		{"feature/playlist/infrastructure", "feature/library/infrastructure"},
		{"feature/library/domain", "feature/playlist/domain"},
		{"feature/stream/presentation", "feature/library/presentation"},
		{"feature/auth/application", "config"},
		{"platform/httpx", "feature/auth/domain"},
		{"shared", "platform/httpx"},
	} {
		if violation(c[0], c[1]) == "" {
			t.Errorf("%s -> %s should be rejected", c[0], c[1])
		}
	}
	for _, c := range [][2]string{
		{"feature/library/application", "feature/library/domain"},
		{"feature/library/infrastructure", "platform/sqlitex"},
		{"feature/playlist/application", "feature/library/domain"},
		{"feature/playlist/presentation", "feature/library/presentation"},
		{"app", "feature/library/infrastructure"},
	} {
		if v := violation(c[0], c[1]); v != "" {
			t.Errorf("%s -> %s should be allowed: %s", c[0], c[1], v)
		}
	}
}
