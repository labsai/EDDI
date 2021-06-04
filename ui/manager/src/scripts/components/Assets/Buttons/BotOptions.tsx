import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import { Dropdown } from 'semantic-ui-react';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import Radium from 'radium';
import { GREY_COLOR } from '../../../../styles/DefaultStylingProperties';
import { IBot } from '../../utils/AxiosFunctions';
import modalActionDispatchers from '../../../actions/ModalActionDispatchers';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import { NOT_FOUND, READY } from '../../utils/helpers/BotHelper';
import styles from './Options.styles';
import { readOnlySelector } from '../../../selectors/AuthenticationSelectors';
import { connect } from 'react-redux';

interface IPublicProps {
  bot: IBot;
  apiUrl: string;
}

interface IPrivateProps extends IPublicProps {
  readOnly: boolean;
}

const trigger = (
  <FontAwesomeIcon
    style={styles.trigger}
    icon={['fas', 'ellipsis-v']}
    color={GREY_COLOR}
  />
);

class BotOptions extends React.Component<IPrivateProps> {
  render() {
    const { bot } = this.props;
    const botDeployed = bot.deploymentStatus === READY;
    const botUndeployed = bot.deploymentStatus === NOT_FOUND;
    const isCurrentVersion = bot.version === bot.currentVersion;
    return (
      <div style={styles.optionButton}>
        <Dropdown trigger={trigger} icon={null}>
          <Dropdown.Menu>
            <Dropdown.Item
              text={'Open Chat'}
              icon={'chat'}
              disabled={!botDeployed}
              onClick={() =>
                window
                  .open(
                    `${this.props.apiUrl}/chat/unrestricted/${bot.id}`,
                    '_blank',
                  )
                  .focus()
              }
            />
            <Dropdown.Item
              text={'View Conversations'}
              icon={'comments outline'}
              onClick={() => {
                eddiApiActionDispatchers.fetchConversationsAction(
                  20,
                  0,
                  null,
                  this.props.bot.resource,
                );
                modalActionDispatchers.showConversationsModal(this.props.bot);
              }}
            />
            <Dropdown.Item
              text={botDeployed ? 'Undeploy' : 'Deploy'}
              icon={
                botDeployed
                  ? 'arrow alternate circle down outline'
                  : 'arrow alternate circle up outline'
              }
              disabled={(!botDeployed && !botUndeployed) || this.props.readOnly}
              onClick={() =>
                (botUndeployed &&
                  eddiApiActionDispatchers.deployBotAction(bot.resource)) ||
                (botDeployed &&
                  modalActionDispatchers.showConfirmationModal(
                    `Are you sure you want to undeploy ${bot.name}?`,
                    null,
                    () =>
                      eddiApiActionDispatchers.undeployBotAction(bot.resource),
                  ))
              }
            />
            <Dropdown.Item
              text={'Rename'}
              icon={'edit outline'}
              disabled={!isCurrentVersion || this.props.readOnly}
              onClick={() =>
                modalActionDispatchers.showEditDescriptorModalAction(bot)
              }
            />
            <Dropdown.Item
              text={'Edit JSON'}
              icon={'edit'}
              disabled={!isCurrentVersion || this.props.readOnly}
              onClick={() =>
                modalActionDispatchers.showEditJsonModal(
                  bot.resource,
                  JSON.stringify(
                    {
                      packages: bot.packages,
                      channels: bot.channels,
                    },
                    null,
                    '\t',
                  ),
                )
              }
            />
            <Dropdown.Item>
              <Dropdown text={'Duplicate'} disabled={this.props.readOnly}>
                <Dropdown.Menu>
                  <Dropdown.Item
                    text={'Normal'}
                    icon={'copy outline'}
                    onClick={() =>
                      eddiApiActionDispatchers.duplicateAction(
                        bot.resource,
                        false,
                      )
                    }
                    disabled={this.props.readOnly}
                  />
                  <Dropdown.Item
                    text={'Deep copy'}
                    icon={'copy'}
                    onClick={() =>
                      eddiApiActionDispatchers.duplicateAction(
                        bot.resource,
                        true,
                      )
                    }
                    disabled={this.props.readOnly}
                  />
                </Dropdown.Menu>
              </Dropdown>
            </Dropdown.Item>
            <Dropdown.Divider />
            <Dropdown.Item text={'Delete'} disabled={true} icon={'delete'} />
          </Dropdown.Menu>
        </Dropdown>
      </div>
    );
  }
}

const ComposedBotOptions: React.ComponentClass<IPublicProps, IPrivateProps> =
  compose<IPrivateProps, IPublicProps>(
    pure,
    connect(readOnlySelector),
    Radium,
    setDisplayName('BotOptions'),
  )(BotOptions);

export default ComposedBotOptions;
