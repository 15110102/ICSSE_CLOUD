/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.a97lynk.service;

import com.a97lynk.object.dto.UserDTO;
import com.a97lynk.object.entity.*;
import com.a97lynk.exception.EmailExistsException;
import com.a97lynk.respository.*;
import com.a97lynk.utils.FineGrained;
import com.a97lynk.utils.TokenStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service
public class UserService implements IUserService {

    private static final int UUID_LENGTH = 36;

    @Autowired
    UserRepository userRepository;

    @Autowired
    VerificationTokenRepository tokenRepository;

    @Autowired
    RoleRepository roleRepository;

    @Autowired
    PrivilegeRepository privilegeRepository;

    @Autowired
    PasswordResetTokenRepository passwordRepository;

    @Autowired
    FineGrained fineGrained;

    @Autowired
    BCryptPasswordEncoder passwordEncoder;

    private static final Logger logger
            = Logger.getLogger(UserService.class.getName());

    @Override
    @Transactional
    public User registerNewUserAccount(UserDTO userDTO)
            throws EmailExistsException {

        if (emailExist(userDTO.getEmail())) {
            throw new EmailExistsException("There is an account with that email address:" + userDTO.getEmail());
        }
        User user = new User();
        user.setFirstName(userDTO.getFirstName());
        user.setLastName(userDTO.getLastName());
        user.setPassword(passwordEncoder.encode(userDTO.getPassword()));
        user.setEmail(userDTO.getEmail());
        user.setRoles(Arrays.asList(roleRepository.findByName("STAFF")));
        logger.info(user.toString());
        return userRepository.save(user);
    }

    private boolean emailExist(String email) {
        User user = userRepository.findByEmail(email);
        if (user != null) {
            return true;
        }
        return false;
    }

    //////////////////
    @Override
    public VerificationToken createVerificationToken(User user) {
        String token = UUID.randomUUID().toString();
        VerificationToken myToken = new VerificationToken(token, user);
        return tokenRepository.save(myToken);
        // return myToken;
    }

    @Override
    public User getUser(String verificationToken) {
        return tokenRepository.findByToken(verificationToken).getUser();
    }

    @Override
    public VerificationToken getVerificationToken(User user) {
        return tokenRepository.findByUser(user);
    }

    @Override
    public void saveRegisteredUser(User user) {
        userRepository.save(user);
    }

    @Override
    public VerificationToken getVerificationToken(String VerificationToken) {
        return tokenRepository.findByToken(VerificationToken);
    }

    @Override
    public VerificationToken generateNewVerificationToken(final String existingVerificationToken) {
        VerificationToken vToken = tokenRepository.findByToken(existingVerificationToken);
        vToken.updateToken(UUID.randomUUID().toString());
        vToken = tokenRepository.save(vToken);
        return vToken;
    }

    @Override
    public TokenStatus validateVerificationToken(String token) {
        // tìm token trong db
        Optional<VerificationToken> verificationToken
                = Optional.of(token).map(tokenRepository::findByToken);

        // token không tồn tại
        if (!verificationToken.isPresent()) {
            return TokenStatus.NOT_EXIST;
        }

        // kiểm tra liệu tài khoản này đã xác nhận chưa
        Optional<User> user = verificationToken.map(VerificationToken::getUser);
        if (user.get().isEnabled()) {
            return TokenStatus.MAIL_ENABLED;
        }

        Calendar cal = Calendar.getInstance();
        // kiểm tra thời hạn token
        if (verificationToken.map(VerificationToken::getExpiryDate).get()
                .before(cal.getTime())) {
            return TokenStatus.EXPIRED;
        }
        return TokenStatus.INVALID;
    }

    @Override
    public User getUserByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    @Override
    public Role getRoleByName(String name) {
        return roleRepository.findByName(name);
    }

    @Override
    public Privilege getPrivilegeByName(String name) {
        return privilegeRepository.findByName(name);
    }

    @Override
    public PasswordResetToken getPasswordResetTokenByUser(User user) {
        return passwordRepository.findByUser(user);
    }

    @Override
    public PasswordResetToken createPasswordResetTokenForUser(User user) {
        String token = UUID.randomUUID().toString();
        PasswordResetToken oldToken = getPasswordResetTokenByUser(user);
        if (oldToken == null) {
            PasswordResetToken myToken = new PasswordResetToken(token, user);
            return passwordRepository.save(myToken);
        } else {
            oldToken.setToken(token);
            return passwordRepository.save(oldToken);
        }
    }

    @Override
    public PasswordResetToken generateNewPasswordResetToken(final String existingPasswordResetToken) {
        PasswordResetToken pToken = passwordRepository.findByToken(existingPasswordResetToken);
        pToken.updateToken(UUID.randomUUID().toString());
        pToken = passwordRepository.save(pToken);
        return pToken;
    }

    @Override
    public TokenStatus validatePasswordResetToken(long userID, String token) {
        if (token == null || token.length() != UUID_LENGTH) {
            return TokenStatus.INVALID;
        }
        PasswordResetToken passToken
                = passwordRepository.findByToken(token);
        if ((passToken == null) || (passToken.getUser()
                .getId() != userID)) {
            return TokenStatus.NOT_EXIST;
        }

        Calendar cal = Calendar.getInstance();
        if (passToken.getExpiryDate().before(cal.getTime())) {
            return TokenStatus.EXPIRED;
        }
        User u = passToken.getUser();

        org.springframework.security.core.userdetails.User user = new org.springframework.security.core.userdetails.User(
                u.getEmail(), u.getPassword(),
                true, true, true, true,
                fineGrained.getAuthorities(u.getRoles()));

        Authentication auth = new UsernamePasswordAuthenticationToken(
                user, null, user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
        return TokenStatus.VALID;
    }

    @Override
    public boolean allowSendConfirmEmail(User user, VerificationToken token) {
        if (user == null)// email ko tồn tại => không gửi
            return false;
        else {
            if (user.isEnabled())// email đã kích hoạt => ko gửi
                return false;
            if(token == null) //  email chưa kích hoạt và ko có token => gửi
                return true;

            // thời hạn còn token >= 22h  => spam => ko gửi
            return !expiryGeaterThan22Hours(Optional.of(token));
        }
    }

    @Override
    public boolean allowSendResetPassEmail(User user, PasswordResetToken token) {
        if (user == null)// email ko tồn tại => không gửi
            return false;
        else {
            if (!user.isEnabled())// email chưa kích hoạt => ko gửi
                return false;
            if(token == null) //  email đã kích hoạt và ko có token => gửi
                return true;

            // thời hạn còn token >= 22h  => spam => ko gửi
            return !expiryGeaterThan22Hours(Optional.of(token));
        }
    }

    // thời hạn còn lại của token >= 22h
    private <T> boolean expiryGeaterThan22Hours(Optional<T> token) {

        if (!(token.get() instanceof Token)) { // giá trị ko phải token
            logger.log(Level.INFO, ">> Token is {0}",
                    ((token.get() instanceof VerificationToken) ? "VerificationToken" : "PasswordResetToken"));
            return false;
        }

        // lấy thời gian hết hạn
        Optional<Date> expiryDate = Optional.empty();
        if (token.get() instanceof VerificationToken) {
            expiryDate = token.map(t -> {
                return ((VerificationToken) t).getExpiryDate();
            });
        }
        if (token.get() instanceof PasswordResetToken) {
            expiryDate = token.map(t -> {
                return ((PasswordResetToken) t).getExpiryDate();
            });

        }
        // chuyển thành local datetime
        LocalDateTime tokenExpiryTime = LocalDateTime.ofInstant(
                expiryDate.map(Date::toInstant).get(), ZoneId.systemDefault());

        // thời gian hiện tại
        LocalDateTime nowTime = LocalDateTime.now();

        // tính thời gian còn lại
        Duration duration = Duration.between(nowTime, tokenExpiryTime);

        logger.log(Level.INFO, ">> Expire is {0} h", duration.toHours());
        if (duration.toHours() >= 22) {
            return true;
        }

        return false;
    }

}
