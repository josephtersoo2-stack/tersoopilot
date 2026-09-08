import json
from django.contrib.auth import authenticate
from django.contrib.auth.models import User
from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework import status
from rest_framework.authtoken.models import Token
from rest_framework.permissions import IsAuthenticated, AllowAny

class RegisterView(APIView):
    permission_classes = [AllowAny]

    def post(self, request):
        username = request.data.get("username", "").strip()
        password = request.data.get("password", "").strip()
        email = request.data.get("email", "").strip()

        if not username or not password:
            return Response(
                {"error": "Username and password are required.", "code": "MISSING_CREDENTIALS"},
                status=status.HTTP_400_BAD_REQUEST
            )

        if len(password) < 4:
            return Response(
                {"error": "Password must be at least 4 characters long.", "code": "PASSWORD_TOO_SHORT"},
                status=status.HTTP_400_BAD_REQUEST
            )

        if User.objects.filter(username__iexact=username).exists():
            return Response(
                {
                    "error": f"Username '{username}' is already registered. Please tap 'Sign In' instead.",
                    "code": "USER_ALREADY_EXISTS"
                },
                status=status.HTTP_400_BAD_REQUEST
            )

        try:
            user = User.objects.create_user(username=username, password=password, email=email)
            token, _ = Token.objects.get_or_create(user=user)
            return Response(
                {
                    "status": "success",
                    "message": "Account created successfully.",
                    "token": token.key,
                    "user": {
                        "id": user.id,
                        "username": user.username,
                        "email": user.email,
                    }
                },
                status=status.HTTP_201_CREATED
            )
        except Exception as e:
            return Response(
                {"error": f"Failed to create account: {str(e)}", "code": "SERVER_ERROR"},
                status=status.HTTP_500_INTERNAL_SERVER_ERROR
            )


class LoginView(APIView):
    permission_classes = [AllowAny]

    def post(self, request):
        username = request.data.get("username", "").strip()
        password = request.data.get("password", "").strip()

        if not username or not password:
            return Response(
                {"error": "Username and password are required.", "code": "MISSING_CREDENTIALS"},
                status=status.HTTP_400_BAD_REQUEST
            )

        existing_user = User.objects.filter(username__iexact=username).first()
        if not existing_user:
            return Response(
                {
                    "error": f"Account '{username}' was not found. Please tap the 'Register' tab to create this account.",
                    "code": "USER_NOT_FOUND"
                },
                status=status.HTTP_400_BAD_REQUEST
            )

        user = authenticate(username=existing_user.username, password=password)
        if not user:
            return Response(
                {
                    "error": "Incorrect password. Please verify your password and try again.",
                    "code": "INVALID_PASSWORD"
                },
                status=status.HTTP_400_BAD_REQUEST
            )

        token, _ = Token.objects.get_or_create(user=user)
        return Response(
            {
                "status": "success",
                "message": "Logged in successfully.",
                "token": token.key,
                "user": {
                    "id": user.id,
                    "username": user.username,
                    "email": user.email,
                }
            },
            status=status.HTTP_200_OK
        )


class UserMeView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request):
        user = request.user
        profile_count = user.profiles.count()
        return Response(
            {
                "id": user.id,
                "username": user.username,
                "email": user.email,
                "profile_count": profile_count,
            },
            status=status.HTTP_200_OK
        )
