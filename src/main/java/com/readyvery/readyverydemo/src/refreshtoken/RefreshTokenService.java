package com.readyvery.readyverydemo.src.refreshtoken;

public interface RefreshTokenService {

	void removeRefreshTokenInRedis(String email);

	void saveRefreshTokenInRedis(String email, String refreshToken);
}
