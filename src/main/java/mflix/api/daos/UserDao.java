package mflix.api.daos;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import mflix.api.models.Session;
import mflix.api.models.User;
import mflix.api.models.constants.SessionConstants;
import mflix.api.models.constants.UserConstants;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.function.Supplier;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.set;
import static org.bson.codecs.configuration.CodecRegistries.fromProviders;
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries;

@Configuration
//add informative logs on important steps
public class UserDao extends AbstractMFlixDao {
    private final MongoCollection<User> usersCollection;
    private final MongoCollection<Session> sessionsCollection;

    private final Logger log;

    @Autowired
    public UserDao(
            MongoClient mongoClient, @Value("${spring.mongodb.database}") String databaseName) {
        super(mongoClient, databaseName);
        CodecRegistry pojoCodecRegistry =
                fromRegistries(
                        MongoClientSettings.getDefaultCodecRegistry(),
                        fromProviders(PojoCodecProvider.builder().automatic(true).build()));

        usersCollection = db.getCollection(UserConstants.COLLECTION_NAME, User.class).withCodecRegistry(pojoCodecRegistry);
        sessionsCollection = db.getCollection(SessionConstants.COLLECTION_NAME, Session.class).withCodecRegistry(pojoCodecRegistry);

        log = LoggerFactory.getLogger(this.getClass());
    }

    /**
     * Inserts the `user` object in the `users` collection.
     *
     * @param user - User object to be added
     * @return True if successful, throw IncorrectDaoOperation otherwise
     */
    public boolean addUser(User user) {
        try {
            //check if user with email exists and throw exception. Think which one is preferable - checked or unchecked
            usersCollection.withWriteConcern(WriteConcern.MAJORITY).insertOne(user);
        } catch (MongoWriteException e) {
            //this is write exception , not neccesary exception of user existence. Make generic. Add error logging
            throw new IncorrectDaoOperation("The user is already exists");
        }

        return true;
    }

    /**
     * Creates session using userId and jwt token.
     *
     * @param userId - user string identifier
     * @param jwt    - jwt string token
     * @return true if successful
     */
    public boolean createUserSession(String userId, String jwt) {
        //check if session with this jwt already exists
        UpdateResult updateResult = writeToMongoDBSafely(
                () -> sessionsCollection.updateOne(
                        eq(SessionConstants.USER_ID, userId),
                        set(SessionConstants.JWT, jwt),
                        new UpdateOptions().upsert(true)
                )
        );
        return updateResult.wasAcknowledged();
    }

    /**
     * Returns the User object matching the an email string value.
     *
     * @param email - email string to be matched.
     * @return User object or null.
     */
    public User getUser(String email) {
        return usersCollection.find(eq(UserConstants.EMAIL, email)).first();
    }

    /**
     * Given the userId, returns a Session object.
     *
     * @param userId - user string identifier.
     * @return Session object or null.
     */
    public Session getUserSession(String userId) {
        return sessionsCollection.find(eq(SessionConstants.USER_ID, userId)).first();
    }

    public boolean deleteUserSessions(String userId) {
        DeleteResult deleteResult = sessionsCollection.deleteMany(eq(SessionConstants.USER_ID, userId));
        return deleteResult.wasAcknowledged();
    }

    /**
     * Removes the user document that match the provided email.
     *
     * @param email - of the user to be deleted.
     * @return true if user successfully removed
     */
    public boolean deleteUser(String email) {
        //check if this user exists before you are trying to delete it
        DeleteResult userDeleteResult =
                writeToMongoDBSafely(() -> usersCollection.deleteOne(eq(UserConstants.EMAIL, email)));

        if (userDeleteResult.getDeletedCount() == 0) {
            //invalid type of exception
            throw new IncorrectDaoOperation("User doesn't exist.");
        }

        DeleteResult sessionsDeleteResult =
                writeToMongoDBSafely(() -> sessionsCollection.deleteMany(eq(SessionConstants.USER_ID, email)));

        return userDeleteResult.wasAcknowledged() && sessionsDeleteResult.wasAcknowledged();
    }

    /**
     * Updates the preferences of a user identified by `email` parameter.
     *
     * @param email           - user to be updated email
     * @param userPreferences - set of preferences that should be stored and replace the existing
     *                        ones. Cannot be set to null value
     * @return User object that just been updated.
     */
    public boolean updateUserPreferences(String email, Map<String, ?> userPreferences) {
        if (userPreferences == null) {
            throw new IncorrectDaoOperation("userPreferences can't be null");
        }
        //check if user exists

        UpdateResult updateResult = writeToMongoDBSafely(() -> usersCollection.updateOne(
                eq(UserConstants.EMAIL, email),
                set(UserConstants.PREFERENCES, userPreferences)
        ));

        return updateResult.wasAcknowledged();
    }

    private <T> T writeToMongoDBSafely(Supplier<T> writeOperation) {
        try {
            return writeOperation.get();
        } catch (MongoWriteException e) {
            throw new IncorrectDaoOperation(e);
        }
    }
}
