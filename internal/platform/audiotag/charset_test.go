package audiotag

import "testing"

func TestRepairText(t *testing.T) {
	tests := []struct{ in, hint, want string }{
		{"³Ì¦nªºµ²§½", "/x/八三夭樂團-最好的結局.mp3", "最好的結局"},     // Big5 as Latin-1
		{"Ö±µ½ÎÒÓö¼û", "/x/200552716392843.mp3", "直到我遇见"}, // GBK as Latin-1
		{"蜷帙→螟上ヵ繧ｧ繧ｹ", "", "君と夏フェス"},                      // UTF-8 read as Shift-JIS
		{"Beyoncé", "", "Beyoncé"},
		{"Plain ASCII", "", "Plain ASCII"},
		{"幻想の城", "", "幻想の城"},
	}
	for _, tt := range tests {
		got, ok := RepairText(tt.in, tt.hint)
		if !ok || got != tt.want {
			t.Errorf("RepairText(%q) = %q,%v want %q", tt.in, got, ok, tt.want)
		}
	}
}
