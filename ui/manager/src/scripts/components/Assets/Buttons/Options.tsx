import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { CSSProperties } from 'react';
import { Dropdown } from 'semantic-ui-react';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import * as Radium from 'radium';
import { GREY_COLOR } from '../../../../styles/DefaultStylingProperties';
import { IDetailedDescriptor } from '../../utils/AxiosFunctions';
import modalActionDispatchers from '../../../actions/ModalActionDispatchers';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';

const styles: CSSProperties = {
  optionButton: {
    ':hover': {
      backgroundColor: '#E0E5EE',
    },
    height: '28px',
    width: '28px',
    border: '1px solid transparent',
    borderRadius: '10px',
    alignContent: 'center',
  },
  dropdown: {},
  trigger: {
    marginTop: '1px',
    marginLeft: '1px',
    width: '24px',
    height: '24px',
  },
};

interface IProps {
  descriptor: IDetailedDescriptor;
  data: string;
}

const trigger = (
  <FontAwesomeIcon
    style={styles.trigger}
    icon={['fas', 'ellipsis-v']}
    color={GREY_COLOR}
  />
);

class Options extends React.Component<IProps> {
  render() {
    const { descriptor } = this.props;
    const isCurrentVersion = descriptor.version === descriptor.currentVersion;
    return (
      <div style={styles.optionButton}>
        <Dropdown style={styles.dropdown} trigger={trigger} icon={null}>
          <Dropdown.Menu>
            <Dropdown.Item
              text={'Rename'}
              icon={'edit outline'}
              disabled={!isCurrentVersion}
              onClick={() =>
                modalActionDispatchers.showEditDescriptorModalAction(descriptor)
              }
            />
            <Dropdown.Item
              text={'Edit JSON'}
              icon={'edit'}
              disabled={!isCurrentVersion}
              onClick={() =>
                modalActionDispatchers.showEditJsonModal(
                  descriptor.resource,
                  this.props.data,
                )
              }
            />
            <Dropdown.Item>
              <Dropdown text={'Duplicate'}>
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

const ComposedOptions: Component<IProps> = compose<IProps>(
  pure,
  Radium,
  setDisplayName('Options'),
)(Options);

export default ComposedOptions;
