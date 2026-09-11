from django.contrib.auth.password_validation import validate_password
from django.core.exceptions import ValidationError
from django.contrib.auth import authenticate
from django.contrib.auth.models import User
from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework import status
from rest_framework.authtoken.models import Token
from rest_framework.permissions import IsAuthenticated, AllowAny

class RegisterView(APIView):
    permission_classes = [AllowAny]
    throttle_scope = "auth"

    def post(self, request):
        username = request.data.get("username", "").strip()
        password = request.data.get("password", "")
        email = request.data.get("email", "").strip()

        if not username or not password:
            return Response(
                {"error": "Username and password are required.", "code": "MISSING_CREDENTIALS"},
                status=status.HTTP_400_BAD_REQUEST
            )

        try:
            validate_password(password, User(username=username, email=email))
        except ValidationError as exc:
            return Response({"error": " ".join(exc.messages), "code": "WEAK_PASSWORD"}, status=400)

        if User.objects.filter(username__iexact=username).exists():
            return Response(
                {
                    "error": "This username is not available. Please choose another or sign in.",
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
        except Exception:
            return Response(
                {"error": "An unexpected error occurred. Please try again later.", "code": "SERVER_ERROR"},
                status=status.HTTP_500_INTERNAL_SERVER_ERROR
            )


class LoginView(APIView):
    permission_classes = [AllowAny]
    throttle_scope = "auth"

    def post(self, request):
        username = request.data.get("username", "").strip()
        password = request.data.get("password", "")

        if not username or not password:
            return Response(
                {"error": "Username and password are required.", "code": "MISSING_CREDENTIALS"},
                status=status.HTTP_400_BAD_REQUEST
            )

        existing_user = User.objects.filter(username__iexact=username).first()
        user = authenticate(username=existing_user.username if existing_user else username, password=password)
        if not user:
            return Response({"error": "Invalid username or password.", "code": "INVALID_CREDENTIALS"}, status=400)

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
                "is_staff": user.is_staff,
            },
            status=status.HTTP_200_OK
        )


class LogoutView(APIView):
    permission_classes = [IsAuthenticated]

    def post(self, request):
        Token.objects.filter(user=request.user).delete()
        return Response({"status": "logged_out"})
