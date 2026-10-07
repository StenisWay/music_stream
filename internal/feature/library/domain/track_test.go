package domain

import "testing"

func TestKeepCuration(t *testing.T) {
	prev := Track{Title: "勇氣 (Live)", Artist: "梁靜茹", UserEdited: true}
	next := Track{Title: "勇氣", Artist: "Fish Leong"}
	next.KeepCuration(prev)
	if next.Title != "勇氣 (Live)" || next.Artist != "梁靜茹" || !next.UserEdited {
		t.Fatalf("user edits must survive a rescan: %+v", next)
	}

	untouched := Track{Title: "新標題"}
	untouched.KeepCuration(Track{Title: "舊標題"})
	if untouched.Title != "新標題" || untouched.UserEdited {
		t.Fatalf("tags win when the user never edited: %+v", untouched)
	}
}

func TestFindDuplicates(t *testing.T) {
	groups := FindDuplicates([]Track{
		{ID: 1, Title: "Ana", Artist: "CLANNAD", ContentHash: "h1"},
		{ID: 2, Title: "Ana", Artist: "CLANNAD", ContentHash: "h1"},         // identical copy
		{ID: 3, Title: "Ana (MV)", Artist: "clannad", ContentHash: "h2"},    // re-upload
		{ID: 4, Title: "Ana (Remix)", Artist: "CLANNAD", ContentHash: "h3"}, // different recording
	})
	if len(groups) != 2 {
		t.Fatalf("want identical + same-song groups, got %+v", groups)
	}
	if groups[0].Reason != DuplicateIdentical || len(groups[0].Tracks) != 2 {
		t.Fatalf("identical group: %+v", groups[0])
	}
	same := groups[1]
	if same.Reason != DuplicateSameSong || len(same.Tracks) != 2 {
		t.Fatalf("same-song group should hold #1 and #3 only: %+v", same)
	}
	for _, tr := range same.Tracks {
		if tr.ID == 2 || tr.ID == 4 {
			t.Fatalf("track %d must not be in the same-song group", tr.ID)
		}
	}
}
