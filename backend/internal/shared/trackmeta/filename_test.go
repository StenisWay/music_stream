package trackmeta

import "testing"

type fakeArtists map[string]bool

func (f fakeArtists) IsKnownArtist(n string) bool { return f[n] }

func TestParseFilename(t *testing.T) {
	known := fakeArtists{"那英": true, "五月天": true, "張學友": true}
	tests := []struct {
		name, hint    string
		artist, title string
		trackNo       int
	}{
		{"張清芳-出塞曲.mp3", "", "張清芳", "出塞曲", 0},
		{"國語老歌-張清芳 - 燃燒一瞬間.mp3", "", "張清芳", "燃燒一瞬間", 0},
		{"周華健 Wakin Chau【讓我歡喜讓我憂 You make me happy and sad】Official Music Video.mp3", "", "周華健", "讓我歡喜讓我憂", 0},
		{"Della丁噹 [ 原來你都在You've Always Been Here ] Official Lyric Video.mp3", "", "丁噹", "原來你都在", 0},
		{"Mayday五月天 [ 如果我們不曾相遇What If We Had Never Met ] Official Music Video.mp3", "", "五月天", "如果我們不曾相遇", 0},
		{"征服 - 那英 na ying.mp3", "", "那英", "征服", 0},
		{"那英 《默》电影《何以笙箫默》主题曲 MV首播 天后那英睽违五年初心回归.mp3", "", "那英", "默", 0},
		{"五月天 (擁抱MV).mp3", "", "五月天", "擁抱", 0},
		{"溫柔 (五月天).mp3", "", "五月天", "溫柔", 0},
		{"A-Lin 我能體諒.mp3", "", "", "A-Lin 我能體諒", 0},
		{"Coldplay-Wherever You Will Go.mp3", "", "Coldplay", "Wherever You Will Go", 0},
		{"Mariah Carey & Ne-Yo - Angels Cry.mp3", "", "Mariah Carey & Ne-Yo", "Angels Cry", 0},
		{"I've Never Been To Me by Charlene with lyrics.mp3", "", "Charlene", "I've Never Been To Me", 0},
		{"07 到山谷的路.wma", "宮崎駿", "宮崎駿", "到山谷的路", 7},
		{"梁靜茹ˇ情歌.mp3", "梁靜茹", "梁靜茹", "情歌", 0},
		{"新兵日記-楊培安-完美世界 .mp3", "楊培安", "楊培安", "完美世界", 0},
		{"[nodrm]-林凡_五天幾年 (犀利人妻 電視劇主題曲).mp3", "", "林凡", "五天幾年", 0},
		{"信 &amp; A-Lin 狂風裡擁抱(MV完整版).mp3", "", "", "信 & A-Lin 狂風裡擁抱", 0},
		{"王力宏 Forever Love.mp3", "", "王力宏", "Forever Love", 0},
		{"方文山電影同名主題曲【聽見下雨的聲音】完整MV 演唱-魏如昀  詞-方文山  曲-周杰倫.mp3", "", "魏如昀", "聽見下雨的聲音", 0},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := ParseFilename(tt.name, tt.hint, known)
			if got.Artist != tt.artist || got.Title != tt.title || got.TrackNo != tt.trackNo {
				t.Errorf("got artist=%q title=%q track=%d, want artist=%q title=%q track=%d",
					got.Artist, got.Title, got.TrackNo, tt.artist, tt.title, tt.trackNo)
			}
		})
	}
}

func TestHintsFromPath(t *testing.T) {
	tests := []struct{ rel, artist, album string }{
		{"音樂/未知的專輯/中文/梁靜茹/梁靜茹-崇拜.mp3", "梁靜茹", ""},
		{"music/宮崎駿/07 到山谷的路.wma", "", "宮崎駿"},
		{"音樂/未知的專輯/再び呟き/シロ/素晴らしき日々/x.mp3", "", "素晴らしき日々"},
		{"music/new2/x.mp3", "", ""},
		{"Downloads/x.mp3", "", ""},
		{"下載/x.mp3", "", ""},
	}
	for _, tt := range tests {
		h := HintsFromPath(tt.rel)
		if h.Artist != tt.artist || h.Album != tt.album {
			t.Errorf("%s: got %+v", tt.rel, h)
		}
	}
}

func TestLibraryPath(t *testing.T) {
	got := LibraryPath(Meta{Title: "a/b?", Artist: "X", Album: "Y", TrackNo: 3}, ".MP3")
	if want := "X/Y/03 - a／b？.mp3"; got != want {
		t.Fatalf("got %q want %q", got, want)
	}
	if got := LibraryPath(Meta{Title: "t"}, ".mp3"); got != UnknownArtist+"/t.mp3" {
		t.Fatalf("got %q", got)
	}
}

func TestResolveInLibrary(t *testing.T) {
	tests := []struct {
		rel                        string
		tags                       Meta
		title, artist, album, aart string
		trackNo                    int
	}{
		{"梁靜茹/無條件為你 (2).mp3", Meta{}, "無條件為你", "梁靜茹", "", "", 0},
		{"CLANNAD/CLANNAD OST DISC1/05 - 有意義な時間の過ごし方.mp3",
			Meta{Title: "有意義な時間の過ごし方", Artist: "CLANNAD", Album: "CLANNAD OST DISC1", TrackNo: 5},
			"有意義な時間の過ごし方", "CLANNAD", "CLANNAD OST DISC1", "", 5},
		{"Key/CLANNAD アルバム/木漏れ日.mp3", Meta{Title: "木漏れ日", Artist: "riya", AlbumArtist: "Key", Album: "CLANNAD アルバム"},
			"木漏れ日", "riya", "CLANNAD アルバム", "Key", 0},
		{"X/a／b.mp3", Meta{Title: "a/b", Artist: "X"}, "a/b", "X", "", "", 0},
		{"Unknown Artist/那些年.mp3", Meta{}, "那些年", "", "", "", 0},
		{"五月天/五月天 - 倔強.mp3", Meta{}, "倔強", "五月天", "", "", 0},
		{"歌曲/華語/梁靜茹/勇氣.mp3", Meta{}, "勇氣", "梁靜茹", "", "", 0},
		{"純音樂/日本/CLANNAD/CLANNAD OST DISC1/05 - 渚.mp3", Meta{}, "渚", "CLANNAD", "CLANNAD OST DISC1", "", 5},
	}
	for _, tt := range tests {
		got, _ := ResolveInLibrary(tt.tags, tt.rel, nil)
		if got.Title != tt.title || got.Artist != tt.artist || got.Album != tt.album || got.AlbumArtist != tt.aart || got.TrackNo != tt.trackNo {
			t.Errorf("%s: got %+v", tt.rel, got)
		}
	}
}
