package trackmeta

import "testing"

func TestSplitCategory(t *testing.T) {
	tests := []struct {
		rel, dir, rest string
		ok             bool
	}{
		{"歌曲/華語/五月天/倔強.mp3", "歌曲/華語", "五月天/倔強.mp3", true},
		{"純音樂/日本/CLANNAD/OST/渚.mp3", "純音樂/日本", "CLANNAD/OST/渚.mp3", true},
		{"五月天/倔強.mp3", "", "五月天/倔強.mp3", false},
		{"歌曲/火星/X/a.mp3", "", "歌曲/火星/X/a.mp3", false},
	}
	for _, tt := range tests {
		c, rest, ok := SplitCategory(tt.rel)
		if ok != tt.ok || rest != tt.rest || (ok && c.Dir() != tt.dir) {
			t.Errorf("%s: got %v %q %v", tt.rel, c, rest, ok)
		}
	}
}

func TestIsInstrumental(t *testing.T) {
	for _, s := range []string{"遙かな年月-piano-", "raison（off vocal）", "Sleeping Butterfly (INST)",
		"Song for friends～instrumental～", "鳥之詩 (鋼琴長版)", "(bonus kara)", "intro", "Classical"} {
		if !IsInstrumental(s) {
			t.Errorf("%q should be instrumental", s)
		}
	}
	for _, s := range []string{"Introduction to Love", "倔強", "Little Busters!", "CLANNAD OST DISC1", "Inside Out"} {
		if IsInstrumental(s) {
			t.Errorf("%q should not be instrumental", s)
		}
	}
}

func TestGuessRegion(t *testing.T) {
	tests := []struct {
		values []string
		want   string
	}{
		{[]string{"五月天", "倔強"}, RegionChinese},
		{[]string{"米津玄師", "アイネクライネ"}, RegionJapanese}, // kanji name, kana title
		{[]string{"Adele", "Someone Like You"}, RegionWestern},
		{[]string{"아이유", "Love poem"}, RegionKorean},
		{[]string{"123", ""}, RegionOther},
	}
	for _, tt := range tests {
		if got := GuessRegion(tt.values...); got != tt.want {
			t.Errorf("%v: got %s want %s", tt.values, got, tt.want)
		}
	}
}

func TestClassify(t *testing.T) {
	known := ArtistCategories{}
	known.Add("純音樂/韓國/Yiruma/River Flows in You.mp3")
	known.Add("歌曲/日本/片霧烈火/約束.mp3")
	known.Add("歌曲/日本/片霧烈火/月吼.mp3")
	known.Add("純音樂/日本/片霧烈火/月吼 (off vocal).mp3")

	tests := []struct {
		meta   Meta
		file   string
		artist string
		want   string
	}{
		{Meta{Title: "Kiss the Rain", Artist: "Yiruma"}, "Kiss the Rain.mp3", "yiruma", "純音樂/韓國"},         // known artist, case-insensitive
		{Meta{Title: "新曲", Artist: "片霧烈火"}, "新曲.mp3", "片霧烈火", "歌曲/日本"},                                    // majority wins
		{Meta{Title: "新曲 off vocal", Artist: "片霧烈火"}, "x.mp3", "片霧烈火", "純音樂/日本"},                          // keyword overrides type
		{Meta{Title: "倔強", Artist: "五月天"}, "倔強.mp3", "五月天", "歌曲/華語"},                                      // guessed
		{Meta{Title: "那些年"}, "那些年.mp3", UnknownArtist, "歌曲/華語"},                                           // unknown artist: title decides
		{Meta{Title: "Victory", Artist: "Two Steps", Genre: "Classical"}, "v.mp3", "Two Steps", "純音樂/歐美"}, // genre
	}
	for _, tt := range tests {
		if got := Classify(tt.meta, tt.file, tt.artist, known).Dir(); got != tt.want {
			t.Errorf("%+v: got %s want %s", tt.meta, got, tt.want)
		}
	}
}
