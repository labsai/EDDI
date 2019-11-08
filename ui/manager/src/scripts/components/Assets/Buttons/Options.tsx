import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { CSSProperties } from 'react';
import { Dropdown } from 'semantic-ui-react';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import * as Radium from 'radium';
import {
  GREY_COLOR,
  LIGHT_GREY_COLOR2,
} from '../../../../styles/DefaultStylingProperties';
import { IDetailedDescriptor } from '../../utils/AxiosFunctions';
import modalActionDispatchers from '../../../actions/ModalActionDispatchers';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import styles from './Options.styles';
import * as _ from 'lodash';
import * as renderIf from 'render-if';
import { getTypeFromResource } from '../../utils/ApiFunctions';
import { PACKAGE } from '../../utils/EddiTypes';
import { readOnlySelector } from '../../../selectors/AuthenticationSelectors';
import { connect } from 'react-redux';

interface IPublicProps {
  descriptor: IDetailedDescriptor;
  data: string;
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

class Options extends React.Component<IPrivateProps> {
  fetchData(isPackage: boolean) {
    if (isPackage) {
      eddiApiActionDispatchers.fetchPackageDataAction(
        this.props.descriptor.resource,
      );
    } else {
      eddiApiActionDispatchers.fetchPluginAction(
        this.props.descriptor.resource,
      );
    }
  }

  render() {
    const { descriptor } = this.props;
    const isCurrentVersion = descriptor.version === descriptor.currentVersion;
    const isPackage = getTypeFromResource(descriptor.resource) === PACKAGE;
    return (
      <div style={styles.optionButton}>
        <Dropdown
          trigger={trigger}
          icon={null}
          onClick={() => this.fetchData(isPackage)}>
          <Dropdown.Menu>
            <Dropdown.Item
              text={'Rename'}
              icon={'edit outline'}
              disabled={!isCurrentVersion || this.props.readOnly}
              onClick={() =>
                modalActionDispatchers.showEditDescriptorModalAction(descriptor)
              }
            />
            <Dropdown.Item
              text={'Edit JSON'}
              icon={'edit'}
              disabled={!isCurrentVersion || this.props.readOnly}
              onClick={() =>
                modalActionDispatchers.showEditJsonModal(
                  descriptor.resource,
                  this.props.data,
                )
              }
            />
            {renderIf(isPackage)(() => (
              <Dropdown.Item>
                <Dropdown text={'Duplicate'} disabled={this.props.readOnly}>
                  <Dropdown.Menu>
                    <Dropdown.Item
                      text={'Normal'}
                      icon={'copy outline'}
                      onClick={() =>
                        eddiApiActionDispatchers.duplicateAction(
                          descriptor.resource,
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
                          descriptor.resource,
                          true,
                        )
                      }
                      disabled={this.props.readOnly}
                    />
                  </Dropdown.Menu>
                </Dropdown>
              </Dropdown.Item>
            ))}
            {renderIf(!isPackage)(() => (
              <Dropdown.Item
                text={'Duplicate'}
                icon={'copy outline'}
                onClick={() =>
                  eddiApiActionDispatchers.duplicateAction(
                    descriptor.resource,
                    false,
                  )
                }
                disabled={this.props.readOnly}
              />
            ))}
            <Dropdown.Divider />
            <Dropdown.Item text={'Delete'} disabled={true} icon={'delete'} />
          </Dropdown.Menu>
        </Dropdown>
      </div>
    );
  }
}

const ComposedOptions: Component<IPrivateProps> = compose<IPrivateProps>(
  pure,
  connect(readOnlySelector),
  Radium,
  setDisplayName('Options'),
)(Options);

export default ComposedOptions;
