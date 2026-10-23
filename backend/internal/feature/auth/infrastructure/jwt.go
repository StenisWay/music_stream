package infrastructure

import (
	"errors"
	"fmt"
	"musicstream/internal/shared"
	"strconv"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

const issuer = "musicstream"

// JWT implements application.AccessTokenIssuer with HS256-signed tokens.
type JWT struct {
	secret []byte
	now    func() time.Time
}

func NewJWT(secret []byte) (*JWT, error) {
	if len(secret) < 32 {
		return nil, errors.New("jwt secret must be at least 32 bytes")
	}
	return &JWT{secret: secret, now: time.Now}, nil
}

func (j *JWT) Issue(user shared.UserID, ttl time.Duration) (string, time.Time, error) {
	now := j.now()
	exp := now.Add(ttl)
	tok := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.RegisteredClaims{
		Issuer:    issuer,
		Subject:   strconv.FormatInt(int64(user), 10),
		IssuedAt:  jwt.NewNumericDate(now),
		ExpiresAt: jwt.NewNumericDate(exp),
	})
	s, err := tok.SignedString(j.secret)
	return s, exp, err
}

func (j *JWT) Verify(token string) (shared.UserID, error) {
	var claims jwt.RegisteredClaims
	_, err := jwt.ParseWithClaims(token, &claims, func(*jwt.Token) (any, error) { return j.secret, nil },
		jwt.WithValidMethods([]string{jwt.SigningMethodHS256.Alg()}),
		jwt.WithIssuer(issuer),
		jwt.WithExpirationRequired(),
		jwt.WithTimeFunc(j.now),
	)
	if err != nil {
		return 0, fmt.Errorf("verify access token: %w", err)
	}
	id, err := strconv.ParseInt(claims.Subject, 10, 64)
	if err != nil || id <= 0 {
		return 0, errors.New("invalid subject")
	}
	return shared.UserID(id), nil
}
