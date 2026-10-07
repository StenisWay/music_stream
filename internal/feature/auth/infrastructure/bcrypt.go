package infrastructure

import "golang.org/x/crypto/bcrypt"

// Bcrypt implements application.PasswordHasher.
type Bcrypt struct{ Cost int }

func (b Bcrypt) Hash(password string) (string, error) {
	cost := b.Cost
	if cost == 0 {
		cost = 12
	}
	h, err := bcrypt.GenerateFromPassword([]byte(password), cost)
	return string(h), err
}

func (Bcrypt) Compare(hash, password string) error {
	return bcrypt.CompareHashAndPassword([]byte(hash), []byte(password))
}
