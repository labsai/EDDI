import * as React from 'react';
import ModalActionDispatchers from '../../actions/ModalActionDispatchers';
import FilterComponent from './FilterComponent';
import NavigationComponent from './NavigationComponent';
import { CSSProperties } from 'react';
import { Link } from 'react-router-dom';
import { ModalEnum } from '../utils/ModalEnum';
import BlueButton from '../Assets/Buttons/BlueButton';

const styles: CSSProperties = {
  createNewBotButton: {
    height: '36px',
    marginLeft: '32px',
    marginTop: '7px',
  },
  topBarCenter: {
    flex: '1',
  },
  topBarComponent: {
    display: 'flex',
    height: '50px',
    marginBottom: '15px',
    width: '100%',
  },
};

interface IProps {
  filter(text: string): void;
}

class TopBarComponent extends React.Component<IProps> {
  constructor(props) {
    super(props);
  }

  openModal = () => {
    ModalActionDispatchers.showModal(ModalEnum.createBot);
  };

  render() {
    return (
      <div style={styles.topBarComponent}>
        <NavigationComponent />
        <div style={styles.topBarCenter} />
        <FilterComponent filter={this.props.filter} />
        <BlueButton
          text={'Create new bot'}
          customStyles={styles.createNewBotButton}
          onClick={() => {
            this.openModal();
          }}
          style={styles.createNewBot}>
          {'Create new bot'}
        </BlueButton>
      </div>
    );
  }
}

export default TopBarComponent;
